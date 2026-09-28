/**
 * salesService.js — sales invoicing against branch batch stock.
 *
 * Three things worth knowing before editing:
 *
 * 1. **The customer's type decides everything about pricing.** Wholesaler buys
 *    BOX/STRIP/PCS at regular prices; Special buys the same units at special
 *    prices; Retailer buys PCS only, at the *wholesaler* piece rate. The unit
 *    restriction and the price list are separate rules — a retailer is limited
 *    to pieces but is not on a different price list.
 *
 * 2. **Prices are never computed here.** `/api/sales/pricing` returns them, and
 *    the server prices the invoice again on save from the same rule. This file
 *    only asks and displays. A price computed in the browser is a price a user
 *    can change.
 *
 * 3. **The unit rules are enforced server-side too.** Hiding the BOX option for
 *    a retailer is a convenience, not the control — the API refuses the line
 *    however the request was built.
 */
import apiClient from '../lib/apiClient.js';

export const UNITS = ['BOX', 'STRIP', 'PCS'];

export const CUSTOMER_TYPES = ['Wholesaler', 'Retailer', 'Special'];

/** What each tier may buy, mirroring CustomerPricing on the server. */
export const TIER_UNITS = {
  WHOLESALER: ['BOX', 'STRIP', 'PCS'],
  SPECIAL:    ['BOX', 'STRIP', 'PCS'],
  RETAILER:   ['PCS'],
};

export const TIER_LABELS = {
  WHOLESALER: 'Wholesaler',
  RETAILER:   'Retailer',
  SPECIAL:    'Special',
};

/** How each tier reads as a badge. Special is highlighted; retailer is plain. */
export const TIER_TONES = {
  WHOLESALER: 'teal',
  SPECIAL:    'amber',
  RETAILER:   'grey',
};

export const STAGES = [
  'Submitted', 'Confirmed', 'Delivery Assigned', 'On The Way', 'Delivered', 'Collected',
];

/* ═══════════════════════════════════════════════════════════════════════════
   PRICING
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * What this customer may buy of this product, and at what price.
 *
 * Returns every unit — including ones this customer cannot have — each with
 * `allowed` and a `reason`, so the UI can explain a missing option rather than
 * silently dropping it. Pass `qty` to also get the batch split FEFO would make.
 */
export async function getPricing({ customerId, productId, qty = null, unit = 'PCS' }) {
  const params = { customerId, productId, unit };
  if (qty !== null && qty !== '' && Number(qty) > 0) params.qty = qty;
  return apiClient.get('/api/sales/pricing', params);
}

/* ═══════════════════════════════════════════════════════════════════════════
   INVOICES
   ═══════════════════════════════════════════════════════════════════════════ */

export async function listSales({
  stage = null, paymentStatus = null, customerId = null,
  from = '', to = '', q = '', page = 0, size = 25,
} = {}) {
  const params = { page, size };
  if (stage && stage !== 'ALL') params.stage = stage;
  if (paymentStatus && paymentStatus !== 'ALL') params.paymentStatus = paymentStatus;
  if (customerId) params.customerId = customerId;
  // Sent only when set: the server reads '' as "no filter" for strings, but an
  // empty date would be parsed as a date and reject the request.
  if (from) params.from = from;
  if (to) params.to = to;
  if (q && q.trim()) params.q = q.trim();
  const raw = await apiClient.get('/api/sales', params);
  return {
    items: raw?.content ?? [],
    total: raw?.totalElements ?? 0,
    page: raw?.page ?? 0,
    totalPages: raw?.totalPages ?? 0,
    size: raw?.size ?? size,
  };
}

export async function getSale(id) {
  return apiClient.get(`/api/sales/${id}`);
}

/**
 * Creates the invoice.
 *
 * Line totals, the invoice number and the batch allocation are all absent from
 * the request — the server derives them. Sending a price here would be sending
 * a number the user could have edited.
 */
export async function createSale(payload) {
  return apiClient.post("/api/sales", buildSaleBody(payload));
}

/** The request body both create and update send. */
function buildSaleBody(payload) {
  return ({
    customerId:     payload.customerId,
    branchId:       payload.branchId ?? null,
    transactionDate: payload.transactionDate,
    paymentType:    payload.paymentType,
    deliveryAddress: payload.deliveryAddress || null,
    deliveryCharge: numOrNull(payload.deliveryCharge),
    invoiceDiscPct: numOrNull(payload.invoiceDiscPct),
    givenAmount:    numOrNull(payload.givenAmount),
    remarks:        payload.remarks || null,
    deliveryManId:  payload.deliveryManId ?? null,
    /* Only when the caller says so. Undefined for the Sales page, which keeps
       its invoices on the delivery workflow they belong to. */
    counterSale:    payload.counterSale === true ? true : undefined,
    lines: (payload.lines || []).map((l) => ({
      productId:   l.productId,
      quantity:    Number(l.quantity),
      salesUnit:   l.salesUnit,
      discountPct: numOrNull(l.discountPct),
    })),
  });
}

/**
 * Rewrites an unconfirmed invoice.
 *
 * Allowed only at stage Submitted. The server reverses the previous batch
 * allocation before applying the new lines, so editing does not leak stock.
 */
/**
 * Edits an invoice that is still at Submitted.
 *
 * The unlock token travels as a header, not a body field: it authorises the
 * request rather than describing the invoice.
 */
export async function updateSale(id, payload, unlockToken = null) {
  return apiClient.put(`/api/sales/${id}`, buildSaleBody(payload),
    unlockToken ? { 'X-Unlock-Token': unlockToken } : undefined);
}

/** Whether the signed-in user may edit this saved invoice on their own. */
export async function getEditAuthority(id) {
  const a = await apiClient.get(`/api/sales/${id}/edit-authority`);
  return { canEdit: !!a?.canEdit, needsUnlock: !!a?.needsUnlock };
}

/**
 * A supervisor authorises one correction to one invoice.
 *
 * The password goes out with this call and is never held anywhere. What comes
 * back is good for this invoice, this user, and exactly one save.
 */
export async function unlockInvoice(id, { username, password, reason }) {
  const r = await apiClient.post(`/api/sales/${id}/unlock`, { username, password, reason });
  return { token: r?.unlockToken, validMinutes: r?.validMinutes ?? 10 };
}

export async function finalizeSale(id) {
  return apiClient.post(`/api/sales/${id}/finalize`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   PREVIEW — display only
   The server recomputes all of this on save. If the two ever disagree, the
   server is right and this is the bug.
   ═══════════════════════════════════════════════════════════════════════════ */

export function lineTotal(line) {
  const qty = num(line?.quantity);
  const price = num(line?.unitPrice);
  const gross = round2(qty * price);
  const disc = round2(gross * (num(line?.discountPct) / 100));
  return round2(gross - disc);
}

export function invoiceTotals(lines, invoiceDiscPct = 0, deliveryCharge = 0, givenAmount = 0) {
  const subtotal = round2((lines || []).reduce((s, l) => s + lineTotal(l), 0));
  const invoiceDisc = round2(subtotal * (num(invoiceDiscPct) / 100));
  const payable = round2(subtotal - invoiceDisc + num(deliveryCharge));
  const due = round2(payable - num(givenAmount));
  return { subtotal, invoiceDisc, payable, due };
}

/** How many whole units of `unit` the branch can supply. */
export function maxQtyFor(pricing, unit) {
  const opt = (pricing?.units || []).find((u) => u.unit === unit);
  return opt?.maxQty ?? null;
}

export function unitOption(pricing, unit) {
  return (pricing?.units || []).find((u) => u.unit === unit) || null;
}

/** The units this customer can actually buy, ready for a radio group. */
export function sellableUnits(pricing) {
  return (pricing?.units || []).filter((u) => u.allowed);
}

/* ── numbers ─────────────────────────────────────────────────────────────── */

function round2(v) {
  if (!Number.isFinite(v)) return 0;
  return Math.round((v + Number.EPSILON) * 100) / 100;
}

function num(v, fallback = 0) {
  if (v === null || v === undefined || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function numOrNull(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

export default {
  UNITS, CUSTOMER_TYPES, TIER_UNITS, TIER_LABELS, TIER_TONES, STAGES,
  getPricing, listSales, getSale, createSale, updateSale, finalizeSale,
  getEditAuthority, unlockInvoice,
  lineTotal, invoiceTotals, maxQtyFor, unitOption, sellableUnits,
};
