/**
 * Invoice merge — a customer's open orders combined into one document.
 *
 * Worth knowing before editing: a merge does not create a second sale. The
 * grouping invoice carries the combined total and nothing else — no lines, no
 * stock. The source orders keep both, because they are what the goods actually
 * went out on. Copying their lines upward would double every product report and
 * re-posting would consume the stock twice.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/sales-merges';

const num = (v) => (v == null ? 0 : Number(v));

/**
 * Orders that can still be merged for this customer.
 *
 * Today's only, still at Submitted, not already merged — the server decides
 * all three, so this list is exactly what a merge will accept.
 */
export async function getEligible(customerId) {
  const rows = await apiClient.get(`${BASE}/eligible`, { customerId });
  return (rows ?? []).map(toOrder);
}

export async function listMerges({ from = '', to = '' } = {}) {
  const params = {};
  if (from) params.from = from;
  if (to) params.to = to;
  const rows = await apiClient.get(BASE, params);
  return (rows ?? []).map(toMerge);
}

export async function getMerge(id) {
  return toMerge(await apiClient.get(`${BASE}/${id}`));
}

export async function mergeOrders({ customerId, orderIds, remarks }) {
  return toMerge(await apiClient.post(BASE, {
    customerId, orderIds, remarks: remarks || undefined,
  }));
}

/** Puts the orders back the way they were. Only while still unconfirmed. */
export async function splitMerge(id, reason) {
  return apiClient.post(`${BASE}/${id}/split`, { reason });
}

function toOrder(o) {
  return {
    id: o.id,
    invoiceNo: o.invoiceNo,
    transactionDate: o.transactionDate,
    stage: o.stage,
    paymentType: o.paymentType,
    lineCount: num(o.lineCount),
    subtotal: num(o.subtotal),
    amount: num(o.amount),
    paid: num(o.paid),
  };
}

function toMerge(m) {
  return {
    id: m.id,
    invoiceNo: m.invoiceNo,
    mergeDate: m.mergeDate,
    customerId: m.customerId,
    customerName: m.customerName || '—',
    stage: m.stage,
    cancelled: !!m.cancelled,
    total: num(m.total),
    paid: num(m.paid),
    remarks: m.remarks,
    sources: (m.sources ?? []).map(toOrder),
  };
}

export default { getEligible, listMerges, getMerge, mergeOrders, splitMerge };
