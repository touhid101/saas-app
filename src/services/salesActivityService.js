/**
 * Sales Activity — orders from submission to collection.
 *
 * Every filter goes to the server. The page holds one page of rows at a time,
 * so narrowing them here would search only what happens to be loaded and
 * disagree with the totals shown beside it.
 */
import apiClient from '../lib/apiClient.js';
import { getDeliveryMen } from './masterDataService.js';

const BASE = '/api/sales-activity';

/** The stages an order moves through, in order. Mirrors the server's list. */
export const STAGES = [
  'Submitted', 'Confirmed', 'Delivery Assigned', 'On The Way', 'Delivered', 'Collected',
];

/** Which stages accept a delivery man. */
export const ASSIGNABLE = new Set(['Submitted', 'Confirmed', 'Delivery Assigned', 'On The Way']);

/** Where a document goes when it stops being an order. Not one of the STAGES. */
export const CANCELLED = 'Cancelled';

/** Where the order came from. The server reads '' as no filter. */
export const SOURCES = [
  { value: 'All',  label: 'All orders' },
  { value: 'ECOM', label: 'Marketplace' },
  { value: 'API',  label: 'Counter' },
];

export const emptyFilter = () => ({
  stage: 'All', paymentStatus: 'All', deliveryManId: 'All', source: 'All',
  from: '', to: '', q: '',
});

export const hasActiveFilter = (f) =>
  f.stage !== 'All' || f.paymentStatus !== 'All' || f.deliveryManId !== 'All'
  || (f.source && f.source !== 'All') || !!f.from || !!f.to || !!f.q.trim();

/**
 * Only what the server should actually filter on.
 *
 * "All" is the UI's word for no filter and must not be sent as a value, or the
 * query looks for orders literally staged "All" and returns nothing. The
 * delivery-man picker carries one non-numeric option — `unassigned` — which is
 * its own flag rather than an id.
 */
function params(filter = {}, page = 0, size = 20) {
  const out = { page, size };
  if (filter.stage && filter.stage !== 'All')                 out.stage = filter.stage;
  if (filter.paymentStatus && filter.paymentStatus !== 'All') out.paymentStatus = filter.paymentStatus;
  if (filter.source && filter.source !== 'All')               out.source = filter.source;
  if (filter.from) out.from = filter.from;
  if (filter.to)   out.to   = filter.to;
  if (filter.q && filter.q.trim()) out.q = filter.q.trim();

  if (filter.deliveryManId === 'unassigned')          out.unassigned = true;
  else if (filter.deliveryManId && filter.deliveryManId !== 'All')
    out.deliveryManId = Number(filter.deliveryManId);

  return out;
}

/**
 * One page of orders, already shaped for the table.
 *
 * The server's field names differ from what this screen grew up calling them —
 * it ran on mock objects with `grandTotal`, `paidAmount`, `date`. Normalising
 * once here keeps that vocabulary out of the API and the API's out of the JSX.
 */
export async function listActivity(filter, page = 0, size = 20) {
  const raw = await apiClient.get(BASE, params(filter, page, size));
  const body = apiClient.unwrapPagination(raw);
  return { ...body, items: (body.items ?? []).map(toOrder) };
}

/**
 * Delivery performance per driver.
 *
 * Delivery staff get only their own row — the server scopes it, so this needs
 * no role check of its own.
 */
export async function getDeliveryPerformance({ branchId = null, from = null, to = null } = {}) {
  const params = {};
  if (branchId) params.branchId = branchId;
  if (from) params.from = from;
  if (to)   params.to   = to;
  return apiClient.get(`${BASE}/delivery-performance`, params);
}

/**
 * Delivery dashboard totals — counts per stage plus billed / collected /
 * outstanding, in one call.
 *
 * `ownRoundOnly` tells the screen whose figures these are: the server narrows
 * them to the signed-in driver's round for delivery staff, and a total carrying
 * no such label is indistinguishable from the whole branch's.
 */
export async function getDeliverySummary(branchId = null) {
  return apiClient.get(`${BASE}/summary`, branchId ? { branchId } : null);
}

/**
 * The stages a driver may move an order to.
 *
 * Mirrors the server rule. They report what they know first-hand — that it is
 * on its way, and that it arrived. Confirming is a sales decision, assigning is
 * managements, and Collected follows from taking money rather than being set.
 */
export const DRIVER_STAGES = ["On The Way", "Delivered"];

export const isDriverRole = (roleCode) => roleCode === "ROLE06";

export async function getStageCounts() {
  return apiClient.get(`${BASE}/stage-counts`);
}

/** Active delivery men — exactly those the server will accept on assignment. */
export async function listDeliveryMen(branchId = null) {
  const rows = await getDeliveryMen(branchId);
  return (rows ?? []).map((d) => ({
    id: d.id,
    name: d.empName,
    vehicleNo: d.vehicleNo,
    phone: d.phone,
    initials: (d.empName || '?').split(' ').filter(Boolean).map((s) => s[0]).slice(0, 2).join(''),
  }));
}

export async function assignDeliveryMan(saleId, deliveryManId) {
  return apiClient.post(`${BASE}/${saleId}/assign-delivery-man`, { deliveryManId: Number(deliveryManId) });
}

export async function bulkAssign(saleIds, deliveryManId) {
  return apiClient.post(`${BASE}/bulk-assign-delivery-man`, {
    invoiceIds: saleIds.map(Number),
    deliveryManId: Number(deliveryManId),
  });
}

export async function advanceStage(saleId, stage, deliveryManId = null) {
  const body = { stage };
  if (deliveryManId != null) body.deliveryManId = Number(deliveryManId);
  return apiClient.post(`${BASE}/${saleId}/advance`, body);
}

/**
 * Calls an order off: the server puts the stock back and reverses the journals.
 *
 * Refused once the goods have left the branch or any money has been taken —
 * both are corrected with a sales return instead, and the server says which.
 */
export async function cancel(saleId, reason) {
  return apiClient.post(`${BASE}/${saleId}/cancel`, { reason: reason || undefined });
}

/** True while the server would still accept a cancellation. Mirrors its rule. */
export const isCancellable = (order) =>
  !!order && ['Submitted', 'Confirmed', 'Delivery Assigned'].includes(order.stage)
  && (order.paidAmount || 0) <= 0;

export async function collect(saleId, amount, paymentType) {
  return apiClient.post(`${BASE}/${saleId}/collect`, { amount: Number(amount), paymentType });
}

/** The stage after this one, or null at the end of the run. */
export function nextStage(stage) {
  const i = STAGES.indexOf(stage);
  return i < 0 || i >= STAGES.length - 1 ? null : STAGES[i + 1];
}

/* ── shaping ────────────────────────────────────────────────────────────── */

const num = (v) => (v == null ? 0 : Number(v));

function toOrder(t) {
  const payable = num(t.amountTobePaid);
  const paid    = num(t.givenAmount);
  return {
    // The numeric key every endpoint takes, kept apart from the invoice number
    // a person reads. Conflating them is why the old page could not call an API
    // at all — its `id` was the display string.
    id: t.id,
    invoiceNo: t.invoiceNo,
    customerId: t.customerId,
    customerName: t.customerName || '—',
    date: t.transactionDate,
    stage: t.stage || 'Submitted',
    paymentStatus: t.paymentStatus || 'Unpaid',
    paymentType: t.paymentType,
    subtotal: num(t.subtotal),
    invoiceDiscPct: num(t.invoiceDiscPct),
    deliveryCharge: num(t.deliveryCharge),
    grandTotal: payable,
    paidAmount: paid,
    // Derived rather than read: `due` is absent on some responses, and a blank
    // there reads as "nothing owed", which is the expensive direction to be wrong.
    //
    // Except on a cancelled document, which owes nothing whatever its total
    // says: the goods went back and the journals were reversed, so printing the
    // old figure as due would send somebody chasing an invoice nobody must pay.
    due: (t.status === 'N' || t.stage === CANCELLED)
      ? 0
      : Math.max(0, Math.round((payable - paid) * 100) / 100),
    deliveryManId: t.deliveryManId ?? null,
    deliveryManName: t.deliveryManName || null,
    deliveryAddress: t.deliveryAddress || null,
    branchId: t.branchId,
    remarks: t.remarks,
    // Where it came from, and what the customer calls it. A marketplace order
    // carries a number of its own; the counter and the caller need both.
    source: t.source || 'API',
    ecomOrderNo: t.ecomOrderNo || null,
    // What a driver needs at the door and the ERP's one-line address cannot
    // hold: the pin the customer dropped, and who to ring. [Phase 9]
    deliveryLatitude: t.deliveryLatitude ?? null,
    deliveryLongitude: t.deliveryLongitude ?? null,
    deliveryPhone: t.deliveryPhone || null,
    deliveryRecipient: t.deliveryRecipient || null,
    cancelReason: t.cancelReason || null,
    cancelledBy: t.cancelledBy || null,
    cancelledDate: t.cancelledDate || null,
    lines: (t.lines ?? []).map((l) => ({
      lineId: l.lineId,
      productName: l.productName || `#${l.productId}`,
      batchNo: l.batchNo,
      expiryDate: l.expiryDate,
      qty: num(l.quantity),
      unitMode: l.salesUnit || 'PCS',
      unitPrice: num(l.salesPrice),
      lineTotal: num(l.totalPrice),
    })),
  };
}

export default {
  STAGES, ASSIGNABLE, SOURCES, CANCELLED, emptyFilter, hasActiveFilter,
  listActivity, getStageCounts, listDeliveryMen,
  assignDeliveryMan, bulkAssign, advanceStage, collect, cancel, isCancellable, nextStage,
  getDeliveryPerformance, getDeliverySummary, DRIVER_STAGES, isDriverRole,
};
