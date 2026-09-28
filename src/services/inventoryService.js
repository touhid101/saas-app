/**
 * inventoryService.js — branch-wise batch stock API.
 *
 * Three things worth knowing before editing:
 *
 * 1. **No branchId is sent.** The backend resolves the branch from the JWT.
 *    Only an administrator may pass one, and only to look at another branch;
 *    everyone else gets a 404 for a branch that is not theirs. Sending it from
 *    here by default would be asking the server to trust the client about which
 *    branch's stock to show.
 *
 * 2. **Quantities are pieces.** Every `*Pcs` field is the stored value. `qtyBox`,
 *    `qtyStrip` and `qtyDisplay` are derived server-side from the packing frozen
 *    on each batch, so a report shows the stock actually held even after someone
 *    edits the product's pack size. Do not recompute them here.
 *
 * 3. **Prices come from the server.** Each row carries a `prices` block with all
 *    six sale prices — box, strip, piece; regular and special — derived from the
 *    product's Sales% against the MRP apportioned to that unit. A null price
 *    means the unit is not priced, which is different from priced at zero.
 *
 * This file previously called six endpoints that did not exist
 * (`/summary`, `/stock/{id}`, `/low-stock`, `/out-of-stock`, `/expiring`,
 * `/adjust`) and was imported by nothing. The paths below are the real ones.
 */
import apiClient from '../lib/apiClient.js';

/* ═══════════════════════════════════════════════════════════════════════════
   SHAPES
   ═══════════════════════════════════════════════════════════════════════════ */

/** The six price slots, in the order the reports lay them out. */
export const PRICE_SLOTS = [
  { key: 'boxPrice',          pct: 'boxPct',          mrp: 'boxMrp',   tier: 'Regular', unit: 'Box' },
  { key: 'stripPrice',        pct: 'stripPct',        mrp: 'stripMrp', tier: 'Regular', unit: 'Strip' },
  { key: 'pcsPrice',          pct: 'pcsPct',          mrp: 'pcsMrp',   tier: 'Regular', unit: 'Pcs' },
  { key: 'specialBoxPrice',   pct: 'specialBoxPct',   mrp: 'boxMrp',   tier: 'Special', unit: 'Box' },
  { key: 'specialStripPrice', pct: 'specialStripPct', mrp: 'stripMrp', tier: 'Special', unit: 'Strip' },
  { key: 'specialPcsPrice',   pct: 'specialPcsPct',   mrp: 'pcsMrp',   tier: 'Special', unit: 'Pcs' },
];

/** Movement types, with how each should read in a ledger. */
export const MOVEMENT_LABELS = {
  PURCHASE:        'Purchase',
  PURCHASE_RETURN: 'Purchase Return',
  SALE:            'Sale',
  SALES_RETURN:    'Sales Return',
  ADJUST:          'Adjustment',
  OPENING:         'Opening',
  TRANSFER_IN:     'Transfer In',
  TRANSFER_OUT:    'Transfer Out',
};

// Tones are the design system's own — sage/amber/clay/teal/grey. There is no
// red or green class; clay is the alarm colour.
export const EXPIRY_TONES = { EXPIRED: 'clay', NEAR_EXPIRY: 'amber', OK: 'sage' };
export const STOCK_TONES  = { OUT: 'clay', LOW: 'amber', OK: 'sage' };

/** Movement direction reads as in/out; both tones are neutral, not judgements. */
export const DIRECTION_TONES = { I: 'sage', O: 'clay' };

/* ═══════════════════════════════════════════════════════════════════════════
   READS
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getDashboard(branchId = null) {
  return apiClient.get('/api/inventory/dashboard', branchId ? { branchId } : undefined);
}

/**
 * The filters every report shares: free text (product name, SKU, batch),
 * company, batch, category, product.
 *
 * Blanks are dropped rather than sent as empty strings — an empty string
 * reaches the server as a LIKE on `%%`, which matches everything except rows
 * where the column is null, so a cleared box would silently hide data.
 */
export const FILTER_KEYS = ['search', 'companyId', 'batchNo', 'categoryId', 'productId'];

export function filterParams(filter = {}) {
  const out = {};
  for (const k of FILTER_KEYS) {
    const v = filter[k];
    if (v === null || v === undefined || v === '' || v === 'ALL') continue;
    out[k] = v;
  }
  if (filter.branchId) out.branchId = filter.branchId;
  // Boolean, so it needs its own line — the loop above drops anything falsy,
  // and `false` is the server default anyway. Only send it when true.
  if (filter.inStockOnly) out.inStockOnly = true;
  return out;
}

/** True when nothing is narrowed — lets the UI show a "filtered" badge. */
export function hasActiveFilter(filter = {}) {
  return Object.keys(filterParams(filter)).some((k) => k !== 'branchId');
}

export function emptyFilter() {
  return { search: '', companyId: '', batchNo: '', categoryId: '', productId: '' };
}

/** Companies and batch references that actually hold stock in this branch. */
export async function getLookups(branchId = null) {
  const raw = await apiClient.get('/api/inventory/lookups', branchId ? { branchId } : undefined);
  return {
    companies: raw?.companies ?? [],
    batches: (raw?.batches ?? []).map((b) => b.name).filter(Boolean),
  };
}

export async function getStockLedger({ page = 0, size = 25, ...filter } = {}) {
  return toPage(await apiClient.get(
    '/api/inventory/stock', { page, size, ...filterParams(filter) }), size);
}

export async function getBatchStock({ includeEmpty = false, page = 0, size = 50, ...filter } = {}) {
  return toPage(await apiClient.get(
    '/api/inventory/batches', { page, size, includeEmpty, ...filterParams(filter) }), size);
}

export async function getExpiringBatches({ days = 30, page = 0, size = 50, ...filter } = {}) {
  return toPage(await apiClient.get(
    '/api/inventory/expiry', { days, page, size, ...filterParams(filter) }), size);
}

export async function getSlowMoving({ days = 90, ...filter } = {}) {
  return apiClient.get('/api/inventory/alerts/slow-moving', { ...filterParams(filter), days });
}

export async function getLowStock(filter = {}) {
  return apiClient.get('/api/inventory/alerts/low-stock', filterParams(filter));
}

export async function getOutOfStock(filter = {}) {
  return apiClient.get('/api/inventory/alerts/out-of-stock', filterParams(filter));
}

/** Stock movement ledger — every piece in and out, and the document behind it. */
export async function getMovements({
  fromDate = null, toDate = null, page = 0, size = 50, ...filter
} = {}) {
  const params = { page, size, ...filterParams(filter) };
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  return toPage(await apiClient.get('/api/inventory/movements', params), size);
}

/**
 * Opening → movements → closing, per product, for a period.
 * Covers purchase, purchase return, sale and sales return together.
 */
export async function getMovementSummary({ fromDate = null, toDate = null, ...filter } = {}) {
  const params = { ...filterParams(filter) };
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  return apiClient.get('/api/inventory/movement-summary', params);
}

/** Which batches a sale of this quantity would consume, without consuming them. */
export async function previewAllocation({ productId, qty, unit = 'PCS', branchId = null }) {
  const params = { productId, qty, unit };
  if (branchId) params.branchId = branchId;
  return apiClient.get('/api/inventory/fefo-preview', params);
}

/**
 * Realised profit per product for a period.
 *
 * Revenue as invoiced against the cost of the batches actually consumed — not
 * an average product cost. Rows carry `soldBelowCost` so a loss-making line is
 * visible rather than buried inside a positive total.
 */
export async function getProfit({ fromDate = null, toDate = null, ...filter } = {}) {
  const params = { ...filterParams(filter) };
  if (fromDate) params.fromDate = fromDate;
  if (toDate) params.toDate = toDate;
  return apiClient.get('/api/inventory/profit', params);
}

/** Batches whose stored balance disagrees with their movement history. Admin only. */
export async function reconcile() {
  return apiClient.get('/api/inventory/reconcile');
}

/* ═══════════════════════════════════════════════════════════════════════════
   HELPERS
   ═══════════════════════════════════════════════════════════════════════════ */

function toPage(raw, fallbackSize) {
  return {
    items: raw?.content ?? [],
    total: raw?.totalElements ?? 0,
    page: raw?.page ?? 0,
    pageSize: raw?.size ?? fallbackSize,
    totalPages: raw?.totalPages ?? 0,
    first: raw?.first ?? true,
    last: raw?.last ?? true,
  };
}

/** Today and the first of the month, as the API wants them. */
export function defaultPeriod() {
  const now = new Date();
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return { fromDate: iso(new Date(now.getFullYear(), now.getMonth(), 1)), toDate: iso(now) };
}

/**
 * Net movement for a summary row, recomputed on the client.
 *
 * The server already states `closingPcs` independently and flags `balanced`
 * when the two disagree. This exists so the UI can show the working, not to
 * second-guess the server — if they differ, the server is right and the row is
 * flagged.
 */
export function netMovement(row) {
  return num(row?.purchasedPcs) - num(row?.purchaseReturnedPcs)
       - num(row?.soldPcs) + num(row?.salesReturnedPcs) + num(row?.adjustedPcs);
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

export default {
  getSlowMoving,
  PRICE_SLOTS, MOVEMENT_LABELS, EXPIRY_TONES, STOCK_TONES,
  getDashboard, getStockLedger, getBatchStock, getExpiringBatches,
  getLowStock, getOutOfStock, getMovements, getMovementSummary,
  previewAllocation, reconcile, getProfit, getLookups,
  filterParams, hasActiveFilter, emptyFilter, FILTER_KEYS,
  defaultPeriod, netMovement,
};
