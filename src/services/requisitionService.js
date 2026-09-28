/**
 * Requisitions — what we are short of, and the orders it becomes.
 *
 * The page that shows these used to run entirely on mock data and made no
 * network call at all, so requisitions the Copilot wrote to the database never
 * appeared on it. Everything here talks to the real endpoints.
 *
 * <b>Stage vocabulary.</b> The server stores DRAFT, SUBMITTED,
 * PENDING_APPROVAL, APPROVED, REJECTED, CONVERTED. The screen has always shown
 * "Pending Approval" and friends. The mapping lives here rather than being
 * sprinkled through the components, so exactly one place knows both spellings.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/v1/requisitions';

const num = (v) => (v == null ? 0 : Number(v));

/** Server stage → what a person reads. */
export const STAGE_LABEL = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  PENDING_APPROVAL: 'Pending Approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CONVERTED: 'Converted',
  CANCELLED: 'Cancelled',
};

export const STAGE_TONE = {
  DRAFT: 'grey',
  SUBMITTED: 'amber',
  PENDING_APPROVAL: 'amber',
  APPROVED: 'sage',
  REJECTED: 'clay',
  CONVERTED: 'teal',
  CANCELLED: 'clay',
};

export const label = (stage) => STAGE_LABEL[stage] ?? stage ?? '—';

/**
 * A requisition belongs to a branch, so the list is scoped to one.
 *
 * Omitting `branchId` gets the caller's own branch from the server rather than
 * everything — the safe reading, since a requisition raised at one branch is
 * not another branch's business. `allBranches` opts into the wider view.
 */
export async function listRequisitions({ branchId, allBranches, stage, from, to,
                                         page = 0, size = 50 } = {}) {
  const params = { page, size };
  if (allBranches) params.allBranches = true;
  else if (branchId) params.branchId = branchId;
  if (stage) params.stage = stage;
  if (from) params.from = from;
  if (to) params.to = to;
  const raw = await apiClient.get(BASE, params);
  const p = apiClient.unwrapPagination(raw);
  return { ...p, items: (p.items ?? []).map(toSummary) };
}

export async function getRequisition(id) {
  return toDetail(await apiClient.get(`${BASE}/${id}`));
}

/* ── workflow ───────────────────────────────────────────────────────────── */

/* ── Raising one by hand ───────────────────────────────────────────────────
   The Copilot writes a requisition from the stock ledger; this is the same
   document typed instead. Both land in DRAFT and go through the same approval,
   so a manual line and a suggested one are treated identically from here on.

   No branch is sent. The server files it against the branch on the session, so
   this cannot raise a requisition on another pharmacy's approval queue. */

/**
 * Products from this branch, for line entry.
 *
 * A blank term lists the branch, so the picker can be browsed as well as
 * searched — you do not have to know a name to add something.
 *
 * `activeOnly: false`, unlike the purchase form. A requisition asks for
 * something to be bought; that legitimately includes a product the shop stopped
 * stocking and now wants back. Refusing to list it means the only way to
 * requisition it is to re-create the product first.
 *
 * `limit` bounds a free search only. A request narrowed to one manufacturer
 * returns every product that maker supplies, however many that is — choosing a
 * manufacturer means all of it, and a page of fifty out of eight hundred was
 * both wrong and silent about being wrong.
 */
export async function searchProducts(q, limit = 60, companyId = null) {
  return apiClient.get('/api/purchases/products', {
    q: q || null, limit, activeOnly: false, companyId: companyId || null,
  });
}

/**
 * Manufacturers this branch stocks, each with a product count.
 *
 * Built from the branch's own products, not the company master, so every name
 * offered has something behind it — picking one can never add nothing.
 */
export async function productCompanies() {
  try {
    const rows = await apiClient.get('/api/purchases/product-companies', { activeOnly: false });
    return Array.isArray(rows) ? rows : [];
  } catch {
    return [];
  }
}

/** One line, as the API wants it. */
const toLine = (l) => ({
  pidProduct: Number(l.pidProduct),
  requestedQty: Number(l.requestedQty),
  unit: l.unit || 'PCS',
  estimatedUnitCost:
    l.estimatedUnitCost === null || l.estimatedUnitCost === undefined || l.estimatedUnitCost === ''
      ? null : Number(l.estimatedUnitCost),
  priority: l.priority || 'MEDIUM',
  remarks: l.remarks || null,
});

/** Creates a DRAFT requisition. */
export async function createRequisition({ lines, remarks = null, reqDate = null } = {}) {
  return apiClient.post(BASE, {
    reqDate, source: 'MANUAL', remarks,
    lines: (lines ?? []).map(toLine),
  });
}

/** Replaces the lines on a DRAFT or SUBMITTED requisition. */
export async function updateRequisition(id, { lines, remarks = null } = {}) {
  return apiClient.put(BASE + '/' + id, { remarks, lines: (lines ?? []).map(toLine) });
}

export const submit          = (id) => apiClient.post(`${BASE}/${id}/submit`, {});
export const sendForApproval = (id) => apiClient.post(`${BASE}/${id}/pending-approval`, {});
export const approve = (id, remarks) => apiClient.post(`${BASE}/${id}/approve`, { remarks });
export const reject  = (id, reason)  => apiClient.post(`${BASE}/${id}/reject`, { reason });

/** Changes one line's quantity. The approval screen is where these get trimmed. */
export const setLineQty = (id, lineId, requestedQty, remarks) =>
  apiClient.patch(`${BASE}/${id}/lines/${lineId}/qty`, { requestedQty, remarks });

/**
 * Takes a line off a requisition and returns the requisition without it.
 *
 * Allowed in the same stages as a quantity change — both decide what is being
 * asked for, and after approval that is settled. The server refuses the last
 * remaining line: an empty requisition would pass approval and produce no
 * order, so cancelling is the right verb for that.
 */
export const removeLine = async (id, lineId) =>
  toDetail(await apiClient.delete(`${BASE}/${id}/lines/${lineId}`));

/* ── split ──────────────────────────────────────────────────────────────── */

/**
 * The proposal: which supplier each line would go to, and what it would cost.
 *
 * Creates nothing. `overrides` is `{ lineId: supplierId }` and is sent as a
 * query string because this is a GET — it is a question, not a change, and
 * refreshing it must be free.
 */
export async function splitPreview(id, overrides = {}) {
  const params = {};
  const pairs = Object.entries(overrides).filter(([, v]) => v);
  if (pairs.length) params.overrides = pairs.map(([k, v]) => `${k}:${v}`).join(',');
  return toPreview(await apiClient.get(`${BASE}/${id}/split-preview`, params));
}

/** Creates one draft purchase per supplier. Nothing is received; no stock moves. */
export async function split(id, { overrides = {}, allowPartial = false } = {}) {
  const body = { allowPartial };
  const pairs = Object.entries(overrides).filter(([, v]) => v);
  if (pairs.length) body.overrides = Object.fromEntries(pairs);
  return apiClient.post(`${BASE}/${id}/split`, body);
}

/**
 * The purchase orders this requisition has already become.
 *
 * For reprinting the supplier copies. The split reports them once, in the
 * dialog that confirms it; this is how they are reached afterwards — when a
 * supplier mislays its sheet, or the buyer sends it days later.
 */
export async function splitOrders(id) {
  return (await apiClient.get(`${BASE}/${id}/orders`)) ?? [];
}

/* ── shapes ─────────────────────────────────────────────────────────────── */

function toSummary(r) {
  return {
    id: r.pidRequisition,
    requisitionNo: r.requisitionNo,
    reqDate: r.reqDate,
    stage: r.stage,
    source: r.source,
    lineCount: num(r.lineCount),
    createdAt: r.createdAt,
  };
}

function toDetail(r) {
  const lines = (r?.lines ?? []).map((l) => ({
    lineId: l.pidReqDtl,
    productId: l.pidProduct,
    productName: l.productName,
    companyId: l.pidCompany,
    companyName: l.companyName,
    aiRecommendedQty: l.aiRecommendedQty == null ? null : Number(l.aiRecommendedQty),
    requestedQty: num(l.requestedQty),
    unit: l.unit ?? 'BOX',
    estimatedUnitCost: num(l.estimatedUnitCost),
    priority: l.priority,
    remarks: l.remarks,
    // Live figures — the branch's Current Stock and the last 30 days' sales,
    // read when the requisition is opened; the raise-time snapshot sits beside them.
    currentStock: num(l.currentStock),
    avgMonthlySales: num(l.avgMonthlySales),
    stockAtRaise: l.stockAtRaise == null ? null : Number(l.stockAtRaise),
    salesAtRaise: l.salesAtRaise == null ? null : Number(l.salesAtRaise),
    // Trade price + IGTA per box today: what a purchase-order line will carry,
    // which is exactly what the split prices with.
    unitCostNow: l.unitCostNow == null ? num(l.estimatedUnitCost) : Number(l.unitCostNow),
  }));
  return {
    id: r?.pidRequisition,
    requisitionNo: r?.requisitionNo,
    branchId: r?.pidBranch,
    reqDate: r?.reqDate,
    stage: r?.stage,
    source: r?.source,
    createdByName: r?.createdByName,
    approvedByName: r?.approvedByName,
    approvedAt: r?.approvedAt,
    remarks: r?.remarks,
    rejectionReason: r?.rejectionReason,
    lines,
    // Priced the way the split prices, so the two figures agree unless a price changed.
    estimatedTotal: r?.estimatedTotal != null ? Number(r.estimatedTotal) : lines.reduce((s, l) => s + l.requestedQty * l.unitCostNow, 0),
    estimatedTotalAtRaise: r?.estimatedTotalAtRaise != null ? Number(r.estimatedTotalAtRaise) : lines.reduce((s, l) => s + l.requestedQty * l.estimatedUnitCost, 0),
  };
}

function toPreview(p) {
  const line = (l) => ({
    lineId: l.reqLineId,
    productId: l.productId,
    productName: l.productName,
    companyName: l.companyName,
    boxes: num(l.boxes),
    unit: l.unit,
    unitCost: num(l.unitCost),
    lineTotal: num(l.lineTotal),
    lastPaidPerBox: l.lastPaidPerBox == null ? null : Number(l.lastPaidPerBox),
    supplierId: l.supplierId,
    supplierName: l.supplierName,
    basis: l.basis,
    lastPurchasedOn: l.lastPurchasedOn,
    priority: l.priority,
  });
  return {
    requisitionId: p?.requisitionId,
    requisitionNo: p?.requisitionNo,
    stage: p?.stage,
    lineCount: num(p?.lineCount),
    total: num(p?.total),
    ready: !!p?.ready,
    groups: (p?.groups ?? []).map((g) => ({
      supplierId: g.supplierId,
      supplierName: g.supplierName,
      companyName: g.companyName,
      lineCount: num(g.lineCount),
      total: num(g.total),
      lines: (g.lines ?? []).map(line),
    })),
    unassigned: (p?.unassigned ?? []).map(line),
  };
}

export default {
  listRequisitions, getRequisition,
  createRequisition, updateRequisition, searchProducts, productCompanies,
  submit, sendForApproval, approve, reject, setLineQty, removeLine,
  splitPreview, split, splitOrders,
  STAGE_LABEL, STAGE_TONE, label,
};
