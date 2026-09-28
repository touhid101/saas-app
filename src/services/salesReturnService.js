/**
 * Sales returns — goods coming back, against the invoice that sent them out.
 *
 * The screen never invents a return line. It picks an invoice, the server says
 * what is still returnable on each of its lines, and the client sends back only
 * which line and how much. Batch, expiry and price all come from the sale, so
 * the goods go back into the lot they left from at the value they left at.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/sales-returns';

/**
 * The invoice as a return screen needs it: each line with what *remains*
 * returnable, not what was sold.
 *
 * Those stop being the same number the moment a partial return exists, and
 * offering the sold figure would let someone type a quantity the save refuses —
 * making them discover the real limit by being told no.
 */
export async function getReturnable(saleId) {
  const inv = await apiClient.get(`${BASE}/returnable/${saleId}`);
  return {
    ...inv,
    lines: (inv?.lines ?? []).map((l) => ({
      ...l,
      soldQty: num(l.soldQty),
      alreadyReturned: num(l.alreadyReturned),
      returnableQty: num(l.returnableQty),
      unitPrice: num(l.unitPrice),
      refundPerUnit: num(l.refundPerUnit),
    })),
  };
}

export async function createReturn({ originalSaleId, transactionDate, reason, paymentType, remarks, lines }) {
  return apiClient.post(BASE, {
    originalSaleId,
    transactionDate: transactionDate || undefined,
    reason: reason || undefined,
    paymentType: paymentType || undefined,
    remarks: remarks || undefined,
    // Only lines with something on them. Sending zeros would be rejected by
    // bean validation with a message about a field the user never filled in.
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

/** Invoices a return can be raised against — dispatched, not still drafts. */
export const RETURNABLE_STAGES = [
  'Confirmed', 'Delivery Assigned', 'On The Way', 'Delivered', 'Collected',
];

/** Reasons offered as shortcuts. Free text is still allowed — these are not a taxonomy. */
export const REASONS = [
  'Damaged packaging',
  'Wrong item supplied',
  'Expired or near expiry',
  'Customer changed mind',
  'Excess supplied',
];

/* ── shaping ────────────────────────────────────────────────────────────── */

const num = (v) => (v == null ? 0 : Number(v));

function toReturn(t) {
  return {
    id: t.id,
    invoiceNo: t.invoiceNo,
    // The server puts the invoice being reversed in `challanNo` — the field the
    // shared transaction DTO already had for "the other document".
    againstInvoiceNo: t.challanNo || null,
    transactionDate: t.transactionDate,
    customerId: t.customerId,
    customerName: t.customerName || '—',
    paymentType: t.paymentType,
    stage: t.stage,
    refund: num(t.amountTobePaid),
    remarks: t.remarks,
    lines: (t.lines ?? []).map((l) => ({
      lineId: l.lineId,
      productName: l.productName || `#${l.productId}`,
      batchNo: l.batchNo,
      expiryDate: l.expiryDate,
      qty: num(l.quantity),
      unit: l.salesUnit || 'PCS',
      unitPrice: num(l.salesPrice),
      total: num(l.totalPrice),
    })),
  };
}

export default {
  getReturnable, createReturn, listReturns, getReturn,
  RETURNABLE_STAGES, REASONS,
};
