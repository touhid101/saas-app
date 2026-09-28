/**
 * Purchase returns — goods going back to the supplier.
 *
 * The mirror of the sales return, with one difference that shapes the screen:
 * this removes stock. A customer's return is already at the counter, so it can
 * always be accepted. Sending goods back requires they still be on the shelf, so
 * every line carries two limits — what was bought and not yet returned, and what
 * the batch still holds — and the smaller of them is what may be entered.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/purchase-returns';

/** Reasons offered as shortcuts. Free text is still accepted — not a taxonomy. */
export const REASONS = [
  'Damaged in transit',
  'Wrong item supplied',
  'Short-dated on arrival',
  'Over-supplied',
  'Quality complaint',
];

/**
 * The purchase as a return screen needs it.
 *
 * Quantities are normalised to numbers here so the form can clamp against them
 * without repeating `Number(...)` at every comparison — a place where a stray
 * string silently makes `>` compare lexically.
 */
export async function getReturnable(purchaseId) {
  const inv = await apiClient.get(`${BASE}/returnable/${purchaseId}`);
  return {
    ...inv,
    lines: (inv?.lines ?? []).map((l) => ({
      ...l,
      purchasedQty: num(l.purchasedQty),
      alreadyReturned: num(l.alreadyReturned),
      notYetReturned: num(l.notYetReturned),
      inStockQty: num(l.inStockQty),
      returnableQty: num(l.returnableQty),
      costPerUnit: num(l.costPerUnit),
      creditPerUnit: num(l.creditPerUnit),
    })),
  };
}

export async function createReturn({ originalPurchaseId, transactionDate, reason, paymentType, remarks, lines }) {
  return apiClient.post(BASE, {
    originalPurchaseId,
    transactionDate: transactionDate || undefined,
    reason: reason || undefined,
    paymentType: paymentType || undefined,
    remarks: remarks || undefined,
    // Only lines with a quantity. Sending zeros would be rejected by bean
    // validation, naming a field the user never filled in.
    lines: (lines ?? [])
      .filter((l) => Number(l.quantity) > 0)
      .map((l) => ({ originalLineId: l.originalLineId, quantity: Number(l.quantity) })),
  });
}

export async function listReturns({ q = '', from = '', to = '', page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (q && q.trim()) params.q = q.trim();
  if (from) params.from = from;
  if (to)   params.to   = to;
  const body = apiClient.unwrapPagination(await apiClient.get(BASE, params));
  return { ...body, items: (body.items ?? []).map(toReturn) };
}

export async function getReturn(id) {
  return toReturn(await apiClient.get(`${BASE}/${id}`));
}

/**
 * Purchases a return can be raised against.
 *
 * Only approved ones: an unapproved purchase has received nothing into stock,
 * so there is nothing to send back — the correction there is an edit.
 */
export const RETURNABLE_STATUS = 'A';

const num = (v) => (v == null ? 0 : Number(v));

function toReturn(t) {
  return {
    id: t.id,
    invoiceNo: t.invoiceNo,
    // The server puts the purchase being reversed in `challanNo` — the field the
    // shared transaction DTO already carries for "the other document".
    againstInvoiceNo: t.challanNo || null,
    transactionDate: t.transactionDate,
    supplierId: t.supplierId,
    supplierName: t.supplierName || '—',
    paymentType: t.paymentType,
    status: t.status,
    credit: num(t.amountTobePaid),
    remarks: t.remarks,
    lines: (t.lines ?? []).map((l) => ({
      lineId: l.lineId,
      productName: l.productName || `#${l.productId}`,
      batchNo: l.batchNo,
      expiryDate: l.expiryDate,
      qty: num(l.quantity),
      unit: l.salesUnit || 'PCS',
      unitCost: num(l.mnpPrice),
      total: num(l.totalPrice),
    })),
  };
}

export default { getReturnable, createReturn, listReturns, getReturn, REASONS, RETURNABLE_STATUS };
