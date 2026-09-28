/**
 * purchaseService.js — Purchase invoice API layer.
 *
 * Two things worth knowing before editing:
 *
 * 1. No orgId, companyId or branchId is invented here. The backend resolves the
 *    tenant from the JWT; branchId is sent only when the user deliberately picks
 *    a branch other than their default.
 *
 * 2. Line totals, invoice totals, the invoice number and the batch reference are
 *    never sent. The server calculates or generates all of them, and the request
 *    DTO has no fields for them. calculateLineTotal() below exists so the form
 *    can show a running total as the user types — it is a preview, not the value
 *    that gets stored.
 */
import apiClient from '../lib/apiClient.js';

export const PURCHASE_TYPES = ['MNP', 'MRP'];
export const SALES_TYPES = ['BOX', 'STRIP', 'PCS'];
export const DEFAULT_EXPIRY_DAYS = 365;

/* ═══════════════════════════════════════════════════════════════════════════
   PRICING PREVIEW
   Mirrors backend PurchaseCalculator. If the two ever disagree, the server is
   right and this function is the bug.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Round half-up to 2 decimals, matching the backend's ROUND(x, 2). */
function round2(value) {
  if (!Number.isFinite(value)) return 0;
  // Number.EPSILON compensates for float representations like 1.005 -> 1.00499…
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function num(value, fallback = 0) {
  if (value === null || value === undefined || value === '') return fallback;
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * Total for one line.
 *
 *   MNP:  base  = ROUND(qty × (tp + igta) − (qty × tp × tpPct/100), 2)
 *         total = ROUND(base − base × disc/100, 2) − qty × flat
 *
 *   MRP:  total = ROUND(qty × (mrp − (mrp × disc/100 + flat)), 2)
 *
 * The branches are asymmetric on purpose — MNP subtracts the flat discount
 * after the final rounding, MRP subtracts it per unit before. That is how the
 * original expression behaves and the backend reproduces it exactly.
 */
export function calculateLineTotal({
  purchaseType, quantity, tpPrice, igta, mrpPrice, tpPct, discountPct, flatDiscount,
}) {
  const qty = num(quantity);
  const disc = num(discountPct);
  const flat = num(flatDiscount);

  if (purchaseType === 'MRP') {
    const mrp = num(mrpPrice);
    return round2(qty * (mrp - ((mrp * disc) / 100 + flat)));
  }

  const tp = num(tpPrice);
  const base = round2(qty * (tp + num(igta)) - (qty * tp * num(tpPct)) / 100);
  const afterDisc = round2(base - (base * disc) / 100);
  return round2(afterDisc - qty * flat);
}

/**
 * Line value before any discount — used to show what the discounts saved.
 *
 * tpPct is zeroed along with the other two. It used to be passed through, so
 * the trade-price cut counted as part of "gross" and never reached the total
 * discount: on PU260900007 a 3% TP cut worth ৳5.62 simply disappeared. All
 * three — TP%, Disc% and Flat — are reductions off the list price, and the
 * total has to include every one of them.
 *
 * On the MRP basis tpPct is unused, so this changes nothing there.
 */
export function calculateLineGross({ purchaseType, quantity, tpPrice, igta, mrpPrice }) {
  return calculateLineTotal({
    purchaseType, quantity, tpPrice, igta, mrpPrice, tpPct: 0, discountPct: 0, flatDiscount: 0,
  });
}

/**
 * Invoice roll-up. The invoice discount applies to the line sum; tax and
 * service charge are added to what remains.
 */
export function calculateInvoiceTotals(lines, header) {
  const purchaseType = header?.purchaseType || 'MNP';

  const totalAmount = round2(lines.reduce(
    (sum, l) => sum + calculateLineTotal({ ...l, purchaseType }), 0));
  const grossAmount = round2(lines.reduce(
    (sum, l) => sum + calculateLineGross({ ...l, purchaseType }), 0));

  const invoiceDisPct = num(header?.invoiceDisPct);
  const invoiceDisAmt = round2((totalAmount * invoiceDisPct) / 100);
  const totalTax = round2(num(header?.totalTax));
  const serviceCharge = round2(num(header?.serviceCharge));
  const givenAmount = round2(num(header?.givenAmount));
  /* [V68] Off the bill after tax and service charge — the same order the server
     applies it in. The two arithmetics have to agree keystroke for keystroke or
     the screen argues with the save. */
  const cashbackAmount = round2(num(header?.cashbackAmount));

  const amountToBePaid = round2(
    totalAmount - invoiceDisAmt + totalTax + serviceCharge - cashbackAmount);
  const lineDiscount = Math.max(round2(grossAmount - totalAmount), 0);

  return {
    totalAmount,
    grossAmount,
    invoiceDisPct,
    invoiceDisAmt,
    /* Returned, not just used to derive the total. The entry screen showed
       only the invoice cut and called it "Discount amount", so a bill with
       ৳900 given away on the lines and ৳100 off the invoice read as a ৳100
       discount. Both halves are now on screen and they add up. */
    lineDiscount,
    totalDiscount: round2(lineDiscount + invoiceDisAmt),
    totalTax,
    serviceCharge,
    cashbackAmount,
    amountToBePaid,
    givenAmount,
    dueAmount: round2(amountToBePaid - givenAmount),
  };
}

/**
 * One unit of the chosen sales type, as a share of a box.
 *
 * The product master prices a BOX — tpPrice, mrpPrice and igta are all box
 * figures — while a line total is quantity in the line's own unit times its
 * price. So a strip or piece line has to carry a scaled-down price, or a single
 * tablet is costed at the price of the box it came out of.
 *
 * The server applies exactly this factor when it saves the line; this exists so
 * the form previews the same number rather than a different one.
 */
export function unitShare(salesType, product) {
  const perBox = Number(product?.pcsPerBox) > 0 ? Number(product.pcsPerBox) : 1;
  return piecesFor(salesType, product) / perBox;
}

/**
 * The box-level prices a line was built from.
 *
 * Always converted from these, never from whatever the line currently shows.
 * Rescaling the displayed price compounds: BOX → STRIP → PCS applied the
 * absolute box share twice and quoted a twelfth of a third of a box.
 *
 * A line reopened from the server has no box prices on it, so they are
 * reconstructed by dividing back out its own unit share — the exact inverse of
 * what the server did when it saved.
 */
function boxPrices(ref) {
  if (ref?.boxTpPrice != null || ref?.boxMrpPrice != null) {
    return { tp: Number(ref.boxTpPrice) || 0, mrp: Number(ref.boxMrpPrice) || 0, igta: Number(ref.boxIgta) || 0 };
  }
  // A product straight from the picker already quotes a box.
  if (!ref?.salesType) {
    return { tp: Number(ref?.tpPrice) || 0, mrp: Number(ref?.mrpPrice) || 0, igta: Number(ref?.igta) || 0 };
  }
  const share = unitShare(ref.salesType, ref) || 1;
  return {
    tp: (Number(ref.tpPrice) || 0) / share,
    mrp: (Number(ref.mrpPrice) || 0) / share,
    igta: (Number(ref.igta) || 0) / share,
  };
}

/** Prices restated for a unit, from the box figures on the product master. */
export function pricesForUnit(salesType, ref) {
  const f = unitShare(salesType, ref);
  const box = boxPrices(ref);
  const at = (v) => Math.round((Number(v) || 0) * f * 10000) / 10000;
  return { tpPrice: at(box.tp), mrpPrice: at(box.mrp), igta: at(box.igta) };
}

/** The box prices to carry on a line, so conversions never compound. */
export function boxPricesFor(ref) {
  const b = boxPrices(ref);
  return { boxTpPrice: b.tp, boxMrpPrice: b.mrp, boxIgta: b.igta };
}

/** Pieces one unit of the chosen sales type represents. */
export function piecesFor(salesType, product) {
  if (!product) return 1;
  if (salesType === 'BOX') return product.pcsPerBox > 0 ? product.pcsPerBox : 1;
  if (salesType === 'STRIP') return product.pcsPerStrip > 0 ? product.pcsPerStrip : 1;
  return 1;
}

/* ═══════════════════════════════════════════════════════════════════════════
   LIST
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Paginated purchase history.
 *
 * Note this does NOT use apiClient.unwrapPagination — that helper expects
 * currentPage/pageSize/hasNext/hasPrevious, which PageResponse.java does not
 * emit, so it would silently return zeros.
 */
export async function getPurchases({
  search = null, supplierId = null, branchId = null, status = null,
  purchaseType = null, productType = null, dateFrom = null, dateTo = null,
  dueOnly = null, page = 0, size = 25,
  sortBy = 'transactionDate', sortDir = 'desc',
} = {}) {
  const params = { page, size, sortBy, sortDir };
  if (search) params.search = search;
  if (supplierId) params.supplierId = supplierId;
  if (branchId) params.branchId = branchId;
  if (status && status !== 'ALL') params.status = status;
  if (purchaseType && purchaseType !== 'ALL') params.purchaseType = purchaseType;
  if (productType && productType !== 'ALL') params.productType = productType;
  if (dateFrom) params.dateFrom = dateFrom;
  if (dateTo) params.dateTo = dateTo;
  if (dueOnly) params.dueOnly = true;

  const raw = await apiClient.get('/api/purchases', params);
  return {
    items: (raw?.content ?? []).map((r) => ({ ...r, advanceAmount: Number(r.advanceAmount) || 0 })),
    total: raw?.totalElements ?? 0,
    page: raw?.page ?? 0,
    pageSize: raw?.size ?? size,
    totalPages: raw?.totalPages ?? 0,
    first: raw?.first ?? true,
    last: raw?.last ?? true,
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   CRUD
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getPurchase(id) {
  return apiClient.get(`/api/purchases/${id}`);
}

export async function createPurchase(values) {
  return apiClient.post('/api/purchases', toRequestBody(values));
}

export async function updatePurchase(id, values) {
  return apiClient.put(`/api/purchases/${id}`, toRequestBody(values));
}

/** Approving receives the stock. Rejects with 409 if already approved. */
export async function approvePurchase(id) {
  return apiClient.post(`/api/purchases/${id}/approve`);
}

/** Rejects with 409 for an approved invoice — raise a return instead. */
export async function cancelPurchase(id) {
  return apiClient.delete(`/api/purchases/${id}`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   LOOKUPS
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getFormLookups() {
  const raw = await apiClient.get('/api/purchases/lookups');
  const defaults = raw?.defaults?.[0] ?? {};
  return {
    suppliers: raw?.suppliers ?? [],
    branches: raw?.branches ?? [],
    purchaseTypes: raw?.purchaseTypes ?? [],
    productTypes: raw?.productTypes ?? [],
    paymentTypes: raw?.paymentTypes ?? [],
    salesTypes: raw?.salesTypes ?? [],
    statuses: raw?.statuses ?? [],
    defaults: {
      purchaseType: defaults.purchaseType ?? 'MNP',
      productType: defaults.productType ?? 'PHARMA',
      paymentType: defaults.paymentType ?? 'CASH',
      status: defaults.status ?? 'P',
      expiryDays: defaults.expiryDays ?? DEFAULT_EXPIRY_DAYS,
    },
  };
}

/** Product rows for line entry, carrying the price snapshot the calculator needs. */
/**
 * Products for line entry, narrowed to what the chosen supplier sells.
 *
 * The supplier id is what is sent, not the company: the server resolves the
 * manufacturer from the supplier itself and ignores any company the caller
 * names. A filter the client applies is a filter the client can drop, and the
 * save refuses a mismatched line anyway — this keeps one from being offered.
 */
export async function searchProducts(q, limit = 25, supplierId = null) {
  return apiClient.get('/api/purchases/products', {
    q: q || null, limit, supplierId: supplierId || null,
  });
}

/* ═══════════════════════════════════════════════════════════════════════════
   FORM <-> API MAPPING
   ═══════════════════════════════════════════════════════════════════════════ */

function toId(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function toDecimal(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function toText(v) {
  if (v === null || v === undefined) return null;
  const t = String(v).trim();
  return t === '' ? null : t;
}

/**
 * Form state -> POST/PUT body.
 *
 * Deliberately omits invoiceNo, batchNo, every totalPrice, and the invoice
 * totals. They are server-owned; sending them is rejected with UNKNOWN_FIELD.
 */
export function toRequestBody(v) {
  return {
    supplierId: toId(v.supplierId),
    branchId: toId(v.branchId),
    transactionDate: v.transactionDate || null,
    challanNo: toText(v.challanNo),
    purchaseType: v.purchaseType || 'MNP',
    productType: v.productType || 'PHARMA',
    paymentType: v.paymentType || 'CASH',
    invoiceDisPct: toDecimal(v.invoiceDisPct),
    totalTax: toDecimal(v.totalTax),
    serviceCharge: toDecimal(v.serviceCharge),
    cashbackAmount: toDecimal(v.cashbackAmount),
    givenAmount: toDecimal(v.givenAmount),
    // The flag rather than the figure: the server then takes the advance from
    // the invoice's own discount, so what is booked as recoverable is the
    // invoice, not a number retyped here.
    payInvoiceDiscount: v.payInvoiceDiscount || undefined,
    advanceAmount: !v.payInvoiceDiscount && Number(v.advanceAmount) > 0
      ? toDecimal(v.advanceAmount) : undefined,
    remarks: toText(v.remarks),
    status: v.status || 'P',
    lines: (v.lines ?? []).map((l) => ({
      productId: toId(l.productId),
      quantity: toDecimal(l.quantity),
      // [V67] Free units. Zero when nobody typed one, never null — the server
      // treats absent as zero anyway, and sending it explicitly keeps the
      // payload readable to anyone debugging a bonus that did not stick.
      bonusQty: toDecimal(l.bonusQty ?? 0),
      // [V69] GIFT — a different product given free. Quantity zero, bonus only.
      lineKind: l.lineKind === 'GIFT' ? 'GIFT' : 'PURCHASE',
      discountPct: toDecimal(l.discountPct),
      flatDiscount: toDecimal(l.flatDiscount),
      tpPct: toDecimal(l.tpPct),
      // batchNo and expiryDate are not sent: the server derives the batch from
      // the invoice's own reference and sets expiry to the purchase date + 365.
      salesType: l.salesType || 'PCS',
    })),
  };
}

/** Today + n days as YYYY-MM-DD in local time. */
export function defaultExpiryDate(days = DEFAULT_EXPIRY_DAYS) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return toDateInput(d);
}

/** Local-time YYYY-MM-DD. toISOString() is UTC and would shift the date. */
export function toDateInput(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}

/** API detail response -> form state, for editing an existing invoice. */
export function toFormValues(purchase, defaults = {}) {
  if (!purchase) {
    return {
      supplierId: '', branchId: '', transactionDate: toDateInput(), challanNo: '',
      purchaseType: defaults.purchaseType ?? 'MNP',
      productType: defaults.productType ?? 'PHARMA',
      paymentType: defaults.paymentType ?? 'CASH',
      invoiceDisPct: '', totalTax: '', serviceCharge: '', cashbackAmount: '', givenAmount: '',
      remarks: '', status: defaults.status ?? 'P', lines: [],
    };
  }
  return {
    supplierId: purchase.supplier?.id ?? '',
    branchId: purchase.branch?.id ?? '',
    transactionDate: purchase.transactionDate ?? toDateInput(),
    challanNo: purchase.challanNo ?? '',
    purchaseType: purchase.purchaseType ?? 'MNP',
    productType: purchase.productType ?? 'PHARMA',
    paymentType: purchase.paymentType ?? 'CASH',
    invoiceDisPct: purchase.invoiceDisPct ?? '',
    totalTax: purchase.totalTax ?? '',
    serviceCharge: purchase.serviceCharge ?? '',
    cashbackAmount: purchase.cashbackAmount ?? '',
    givenAmount: purchase.givenAmount ?? '',
    advanceAmount: purchase.advanceAmount ?? '',
    /* Matched against the invoice discount, which is what the tick hands
       over. A paisa of tolerance: both sides are round2.

       An invoice saved before the rule changed carries the whole discount as
       its advance. Reopened for editing it gets the tick too, so the given
       amount follows the bill plus the invoice discount on the next save
       instead of the form offering the line discounts as an advance again.
       An approved invoice is read-only and shows what was saved. */
    payInvoiceDiscount: Number(purchase.advanceAmount) > 0 && Number(purchase.invoiceDisAmt) > 0
      && (Math.abs(Number(purchase.advanceAmount) - Number(purchase.invoiceDisAmt)) < 0.005
          || (purchase.status !== 'A'
              && Math.abs(Number(purchase.advanceAmount) - Number(purchase.totalDiscount)) < 0.005)),
    remarks: purchase.remarks ?? '',
    status: purchase.status ?? 'P',
    lines: (purchase.lines ?? []).map((l) => ({
      key: `line-${l.id}`,
      productId: l.productId,
      productName: l.productName,
      productCode: l.productCode,
      genericName: l.genericName,
      companyName: l.companyName,
      quantity: l.quantity ?? '',
      bonusQty: l.bonusQty ?? '',
      lineKind: l.lineKind ?? 'PURCHASE',
      tpPrice: l.mnpPrice ?? 0,
      mrpPrice: l.mrpPrice ?? 0,
      igta: l.igta ?? 0,
      tpPct: l.tpPct ?? '',
      discountPct: l.discountPct ?? '',
      flatDiscount: l.flatDiscount ?? '',
      batchNo: l.batchNo ?? '',
      expiryDate: l.expiryDate ?? '',
      salesType: l.salesType ?? 'PCS',
      // Reconstructed from the saved per-unit price so changing the unit on a
      // reopened invoice converts from the box figure, not from itself.
      ...boxPricesFor({
        tpPrice: l.mnpPrice ?? 0, mrpPrice: l.mrpPrice ?? 0, igta: l.igta ?? 0,
        salesType: l.salesType ?? 'PCS',
        pcsPerBox: l.pcsPerBox, pcsPerStrip: l.pcsPerStrip,
      }),
      noOfPcs: l.noOfPcs ?? 1,
    })),
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   CLIENT-SIDE VALIDATION
   Mirrors the server rules for immediate feedback. The server validates
   independently — this is UX, not enforcement.
   ═══════════════════════════════════════════════════════════════════════════ */

export function validateHeader(v) {
  const errors = {};
  if (!toId(v.supplierId)) errors.supplierId = 'Supplier is required.';
  if (!v.purchaseType) errors.purchaseType = 'Costing basis is required.';
  if (v.transactionDate && v.transactionDate > toDateInput()) {
    errors.transactionDate = 'Purchase date cannot be in the future.';
  }
  const disc = num(v.invoiceDisPct);
  if (disc < 0 || disc > 100) errors.invoiceDisPct = 'Must be between 0 and 100.';
  if (num(v.totalTax) < 0) errors.totalTax = 'Cannot be negative.';
  if (num(v.serviceCharge) < 0) errors.serviceCharge = 'Cannot be negative.';
  if (num(v.givenAmount) < 0) errors.givenAmount = 'Cannot be negative.';
  return errors;
}

/** Validates one line. Returns {} when the line is fine. */
export function validateLine(line, purchaseType) {
  const errors = {};
  if (!toId(line.productId)) errors.productId = 'Select a product.';

  /* [V69] A gift is not bought, so it has no purchase quantity — its units are
     all bonus. Each kind is held to its own rule here, mirroring the server, so
     the form refuses the same shapes the save would. */
  const gift = line.lineKind === 'GIFT';
  const qty = num(line.quantity, -1);
  const bonus = num(line.bonusQty, 0);

  if (gift) {
    if (bonus <= 0) errors.bonusQty = 'A gift needs a bonus quantity — that is what was given.';
    if (qty > 0) errors.quantity = 'A gift is not bought. Put the units in Bonus.';
  } else if (line.quantity === '' || line.quantity === null || line.quantity === undefined) {
    errors.quantity = 'Quantity is required.';
  } else if (!Number.isFinite(Number(line.quantity))) {
    errors.quantity = 'Quantity must be a number.';
  } else if (qty <= 0) {
    errors.quantity = 'Quantity must be greater than zero.';
  }

  const disc = num(line.discountPct);
  if (disc < 0 || disc > 100) errors.discountPct = 'Must be between 0 and 100.';
  const tpPct = num(line.tpPct);
  if (tpPct < 0 || tpPct > 100) errors.tpPct = 'Must be between 0 and 100.';
  if (num(line.flatDiscount) < 0) errors.flatDiscount = 'Cannot be negative.';

  if (!line.salesType) errors.salesType = 'Sales type is required.';

  // Nothing to validate for batch or expiry — neither is typed in. The batch
  // comes from the invoice reference and the expiry is a fixed offset.

  if (calculateLineTotal({ ...line, purchaseType }) < 0) {
    errors.flatDiscount = 'Discounts exceed the line value.';
  }
  return errors;
}

export default {
  getPurchases, getPurchase, createPurchase, updatePurchase,
  approvePurchase, cancelPurchase, getFormLookups, searchProducts,
  calculateLineTotal, calculateInvoiceTotals, piecesFor, unitShare, pricesForUnit, boxPricesFor,
  toRequestBody, toFormValues, validateHeader, validateLine,
  defaultExpiryDate, toDateInput,
};
