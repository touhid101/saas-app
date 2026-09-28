/**
 * salesPercentService.js — Sales% (per-unit sale pricing from MRP).
 *
 * Implements the SRS rule for display:
 *     Unit Sales Price = MRP − (MRP × Sales Percentage / 100)
 *
 * The six percentages map to POS_PRODUCT_INFO columns:
 *     SALES_PER            BOX   %  regular
 *     SALES_PER_LEAF       STRIP %  regular
 *     SALES_PER_PCS        PCS   %  regular
 *     SALES_PER_SPC        BOX   %  special customer
 *     SALES_PER_SPC_LEAF   STRIP %  special customer
 *     SALES_PER_SPC_RET    PCS   %  special customer
 *
 * Prices shown in the grid are computed here for instant feedback while typing,
 * but the server derives them independently and its value is what a response
 * carries. If the two ever disagree, the server is right.
 *
 * Distinct from the older /api/v1/sales-discount rules engine, which decides the
 * discount on a sale *line*. This is pricing.
 */
import apiClient from '../lib/apiClient.js';

/** The six slots, in the order the grid lays them out. */
export const SLOTS = [
  { key: 'box',          field: 'boxPct',          clear: 'clearBox',          tier: 'REGULAR', unit: 'BOX',   label: 'Box',   column: 'SALES_PER' },
  { key: 'strip',        field: 'stripPct',        clear: 'clearStrip',        tier: 'REGULAR', unit: 'STRIP', label: 'Strip', column: 'SALES_PER_LEAF' },
  { key: 'pcs',          field: 'pcsPct',          clear: 'clearPcs',          tier: 'REGULAR', unit: 'PCS',   label: 'PCS',   column: 'SALES_PER_PCS' },
  { key: 'specialBox',   field: 'specialBoxPct',   clear: 'clearSpecialBox',   tier: 'SPECIAL', unit: 'BOX',   label: 'Box',   column: 'SALES_PER_SPC' },
  { key: 'specialStrip', field: 'specialStripPct', clear: 'clearSpecialStrip', tier: 'SPECIAL', unit: 'STRIP', label: 'Strip', column: 'SALES_PER_SPC_LEAF' },
  { key: 'specialPcs',   field: 'specialPcsPct',   clear: 'clearSpecialPcs',   tier: 'SPECIAL', unit: 'PCS',   label: 'PCS',   column: 'SALES_PER_SPC_RET' },
];

export const REGULAR_SLOTS = SLOTS.filter((s) => s.tier === 'REGULAR');
export const SPECIAL_SLOTS = SLOTS.filter((s) => s.tier === 'SPECIAL');

/* ═══════════════════════════════════════════════════════════════════════════
   PRICING PREVIEW  (mirrors backend SalesPriceCalculator)
   ═══════════════════════════════════════════════════════════════════════════ */

function round2(v) {
  if (!Number.isFinite(v)) return 0;
  return Math.round((v + Number.EPSILON) * 100) / 100;
}

function num(v, fallback = 0) {
  if (v === null || v === undefined || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * Sale price for one unit.
 *
 * Returns null when the percentage is unset — deliberately not MRP, because
 * "not priced in this unit" and "priced at full MRP" are different states and
 * the SRS treats the first as not sellable.
 */
export function salePrice(mrp, pct) {
  if (pct === null || pct === undefined || pct === '') return null;
  const base = num(mrp);
  return round2(base - (base * num(pct)) / 100);
}

/** Money taken off MRP at this percentage, or null when unpriced. */
export function discountAmount(mrp, pct) {
  const price = salePrice(mrp, pct);
  return price === null ? null : round2(num(mrp) - price);
}

/** The percentage that produces a target price — lets the user type a price. */
export function percentForPrice(mrp, targetPrice) {
  const base = num(mrp);
  if (base === 0) return null;
  return Math.round(((base - num(targetPrice)) / base) * 100 * 10000) / 10000;
}

/* ═══════════════════════════════════════════════════════════════════════════
   API
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getSalesPercentages({
  search = null, companyId = null, categoryId = null, genericId = null,
  status = null, configured = null, belowCost = false,
  page = 0, size = 25, sortBy = 'productName', sortDir = 'asc',
} = {}) {
  const params = { page, size, sortBy, sortDir };
  if (search) params.search = search;
  if (companyId) params.companyId = companyId;
  if (categoryId) params.categoryId = categoryId;
  if (genericId) params.genericId = genericId;
  if (status && status !== 'ALL') params.status = status;
  if (configured !== null && configured !== 'ALL') params.configured = configured;
  // Sent only when on: the server takes a slower path for it, because the cost
  // it compares against is not a column it can filter in the query.
  if (belowCost) params.belowCost = true;

  const raw = await apiClient.get('/api/sales-percentages', params);
  return {
    items: raw?.content ?? [],
    total: raw?.totalElements ?? 0,
    page: raw?.page ?? 0,
    pageSize: raw?.size ?? size,
    totalPages: raw?.totalPages ?? 0,
    first: raw?.first ?? true,
    last: raw?.last ?? true,
  };
}

export async function getProductSalesPercent(productId) {
  return apiClient.get(`/api/sales-percentages/${productId}`);
}

/**
 * Saves one product's percentages.
 *
 * A slot the user emptied is sent as its clear* flag rather than as null,
 * because an omitted field means "leave unchanged" on the server.
 */
export async function saveSalesPercent(productId, values) {
  return apiClient.put(`/api/sales-percentages/${productId}`, toRequestBody(values));
}

/**
 * Applies one set of percentages to an explicit list of products.
 * For a whole manufacturer use applyBulkByScope — ticking boxes only ever
 * reaches the page on screen.
 */
export async function applyBulk(productIds, values, overwriteExisting = false) {
  return apiClient.post('/api/sales-percentages/bulk', {
    productIds,
    percentages: toRequestBody(values),
    overwriteExisting,
  });
}

/**
 * Applies one set of percentages to every product matching a scope — the
 * company-wise case: a manufacturer quotes a trade rate for its whole range.
 *
 * @param scope {companyId?, categoryId?, genericId?, status?, configured?}
 */
export async function applyBulkByScope(scope, values, overwriteExisting = false) {
  return apiClient.post('/api/sales-percentages/bulk', {
    scope: toScopeBody(scope),
    percentages: toRequestBody(values),
    overwriteExisting,
  });
}

/** How many products a scope would touch, without touching them. */
export async function previewScope(scope) {
  const res = await apiClient.post('/api/sales-percentages/bulk/preview', toScopeBody(scope));
  return res?.matchCount ?? 0;
}

/** Blank selects arrive as "" — the API wants null or a number. */
function toScopeBody(scope) {
  const id = (v) => {
    if (v === '' || v === null || v === undefined) return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  };
  return {
    companyId: id(scope?.companyId),
    categoryId: id(scope?.categoryId),
    genericId: id(scope?.genericId),
    status: scope?.status && scope.status !== 'ALL' ? scope.status : (scope?.status ?? 'ACTIVE'),
    configured: scope?.configured === 'ALL' || scope?.configured === undefined || scope?.configured === null
      ? null
      : scope.configured === true || scope.configured === 'true',
  };
}

export async function getFilterLookups() {
  const raw = await apiClient.get('/api/sales-percentages/lookups');
  return {
    companies: raw?.companies ?? [],
    categories: raw?.categories ?? [],
    generics: raw?.generics ?? [],
    units: raw?.units ?? [],
    tiers: raw?.tiers ?? [],
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   MAPPING
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Form state → request body.
 *
 * An empty string means the user cleared the slot, which becomes the clear*
 * flag; undefined means untouched and is simply omitted.
 */
export function toRequestBody(values) {
  const body = {};
  SLOTS.forEach(({ field, clear, key }) => {
    const v = values?.[key];
    if (v === '' || v === null) {
      body[clear] = true;
    } else if (v !== undefined) {
      const n = Number(v);
      if (Number.isFinite(n)) body[field] = n;
    }
  });
  return body;
}

/** API row → editable form state (percentages only). */
export function toFormValues(row) {
  const out = {};
  SLOTS.forEach(({ key }) => {
    const pct = row?.[key]?.pct;
    out[key] = pct === null || pct === undefined ? '' : pct;
  });
  return out;
}

/** True when the row has at least one percentage set. */
export function isConfigured(row) {
  return SLOTS.some((s) => row?.[s.key]?.configured);
}

/* ═══════════════════════════════════════════════════════════════════════════
   VALIDATION  (mirrors the server; UX only, not enforcement)
   ═══════════════════════════════════════════════════════════════════════════ */

export function validate(values) {
  const errors = {};
  SLOTS.forEach(({ key, label, tier }) => {
    const v = values?.[key];
    if (v === '' || v === null || v === undefined) return;
    const n = Number(v);
    const name = `${tier === 'SPECIAL' ? 'Special ' : ''}${label}`;
    if (!Number.isFinite(n)) errors[key] = `${name} % must be a number.`;
    else if (n < 0) errors[key] = `${name} % cannot be negative.`;
    else if (n > 100) errors[key] = `${name} % cannot exceed 100.`;
  });
  return errors;
}

export default {
  SLOTS, REGULAR_SLOTS, SPECIAL_SLOTS,
  getSalesPercentages, getProductSalesPercent, saveSalesPercent,
  applyBulk, applyBulkByScope, previewScope, getFilterLookups,
  salePrice, discountAmount, percentForPrice,
  toRequestBody, toFormValues, isConfigured, validate,
};
