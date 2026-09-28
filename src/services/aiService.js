/**
 * The AI Copilot's data.
 *
 * One call draws the whole screen. The reorder list, the movers, the watch list
 * and the trend all come from the same aggregate on the server, so asking for
 * them separately would scan the stock ledger four times to render one page.
 *
 * There is no model behind any of this. It is arithmetic over the ledger, and
 * every recommendation arrives with the figures it came from so the buyer can
 * check the working rather than take a number on trust.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ai';

const num = (v) => (v == null ? 0 : Number(v));
/** Null is meaningful here — "no cover" and "zero days of cover" are different. */
const opt = (v) => (v == null ? null : Number(v));

/**
 * `supplierId` narrows everything to one supplier's manufacturer.
 *
 * A supplier distributes for one maker, so a reorder list spanning four of them
 * cannot be ordered as it stands — it is four phone calls. Choosing a supplier
 * first makes the result a single placeable purchase.
 */
export async function getBoard({ branchId, allBranches, supplierId, leadTimeDays = 7,
                                 coverDays = 14, safetyFactor = 1.3, limit = 25 } = {}) {
  const params = { leadTimeDays, coverDays, safetyFactor, limit };
  if (allBranches) params.allBranches = true;
  else if (branchId) params.branchId = branchId;
  if (supplierId) params.supplierId = supplierId;
  return toBoard(await apiClient.get(`${BASE}/board`, params));
}

export async function ask(question, opts = {}) {
  const b = await apiClient.post(`${BASE}/ask`, { question, ...opts });
  return {
    question: b?.question ?? question,
    headline: b?.headline ?? '',
    bullets: b?.bullets ?? [],
    action: b?.action ?? null,
  };
}

/**
 * Raises a requisition from the chosen lines.
 *
 * `lines` is `[{ productId, boxes }]` — the quantity the buyer settled on, not
 * the one that was suggested. The server keeps both on the requisition line, so
 * a short order six months from now can be traced to whichever of the two was
 * wrong.
 */
export async function createRequisition({ lines, remarks, branchId, supplierId,
                                          leadTimeDays, coverDays, safetyFactor }) {
  return apiClient.post(`${BASE}/requisition`, {
    lines, remarks, branchId, supplierId, leadTimeDays, coverDays, safetyFactor,
  });
}

/**
 * A requisition is always for one branch, so "all branches" is analysis only.
 * The screen uses this to explain why the button is off rather than leaving a
 * dead control the user has to guess about.
 */
export const canOrderFor = (allBranches) => !allBranches;

function toBoard(b) {
  return {
    reorder: (b?.reorder ?? []).map(toReorder),
    movers: (b?.movers ?? []).map(toMover),
    watch: (b?.watch ?? []).map(toWatch),
    insights: (b?.insights ?? []).map((i) => ({ ...i, amount: num(i.amount) })),
    trend: (b?.trend ?? []).map((d) => ({ day: d.day, pcs: num(d.pcs) })),
    summary: {
      productsTracked: num(b?.summary?.productsTracked),
      needAttention: num(b?.summary?.needAttention),
      suggestedSpend: num(b?.summary?.suggestedSpend),
      stockValue: num(b?.summary?.stockValue),
      expiringValue: num(b?.summary?.expiringValue),
      deadStockValue: num(b?.summary?.deadStockValue),
      outOfStock: num(b?.summary?.outOfStock),
      historyDays: num(b?.summary?.historyDays),
      thinHistory: !!b?.summary?.thinHistory,
    },
  };
}

function toReorder(r) {
  return {
    ...r,
    pcsPerBox: num(r.pcsPerBox),
    costPerBox: num(r.costPerBox),
    mrpPerBox: num(r.mrpPerBox),
    stockPcs: num(r.stockPcs),
    sellablePcs: num(r.sellablePcs),
    velocityPerDay: num(r.velocityPerDay),
    daysOfCover: opt(r.daysOfCover),
    safetyStockPcs: num(r.safetyStockPcs),
    targetStockPcs: num(r.targetStockPcs),
    deficitPcs: num(r.deficitPcs),
    suggestedBoxes: num(r.suggestedBoxes),
    estimatedCost: num(r.estimatedCost),
    sellingDays: num(r.sellingDays),
    trendPct: opt(r.trendPct),
    expiringPcs: num(r.expiringPcs),
    expiringValue: num(r.expiringValue),
    reasons: r.reasons ?? [],
  };
}

function toMover(m) {
  return {
    ...m,
    revenue: num(m.revenue), margin: num(m.margin), marginPct: num(m.marginPct),
    pcs: num(m.pcs), invoices: num(m.invoices),
    velocityPerDay: num(m.velocityPerDay), trendPct: opt(m.trendPct),
    stockPcs: num(m.stockPcs), daysOfCover: opt(m.daysOfCover),
  };
}

function toWatch(w) {
  return {
    ...w,
    stockPcs: num(w.stockPcs), sellablePcs: num(w.sellablePcs),
    atRiskPcs: num(w.atRiskPcs), atRiskValue: num(w.atRiskValue),
    stockValue: num(w.stockValue),
    daysToExpiry: opt(w.daysToExpiry), idleDays: opt(w.idleDays),
    velocityPerDay: num(w.velocityPerDay),
  };
}

export default { getBoard, ask, createRequisition };
