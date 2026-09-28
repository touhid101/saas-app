/**
 * E-Commerce loyalty points (MOD54) — staff side.
 *
 * docs/ecommerce Phase 13. Sits beside the coupons because both are promotions
 * and both cost the pharmacy money; the difference is that a coupon is a code
 * somebody types once and a point is a balance the customer keeps.
 *
 * There is no default rule and nothing seeded. Until somebody writes one here,
 * nothing earns and nothing can be redeemed — an exchange rate is a business
 * decision, not a sensible default.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ecom/admin/points';

const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

const unwrapPage = (r) => {
  const d = unwrap(r) || {};
  return {
    items: d.content || [],
    page: d.page ?? 0,
    size: d.size ?? 25,
    total: d.totalElements ?? 0,
    totalPages: d.totalPages ?? 1,
  };
};

export const TIERS = [
  { value: 'ALL', label: 'Any customer' },
  { value: 'RETAILER', label: 'Retailers only' },
  { value: 'WHOLESALER', label: 'Wholesalers only' },
  { value: 'SPECIAL', label: 'Special accounts only' },
];

/** Running · Scheduled · Expired · Stopped — the server decides which. */
export const STATE_TONE = { Running: 'sage', Scheduled: 'teal', Expired: 'grey', Stopped: 'clay' };

export async function listRules({ active = '', q = '', page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (active) params.active = active;
  if (q) params.q = q;
  return unwrapPage(await apiClient.get(`${BASE}/rules`, params));
}

export const getRule = (id) => apiClient.get(`${BASE}/rules/${id}`).then(unwrap);
export const createRule = (form) => apiClient.post(`${BASE}/rules`, form).then(unwrap);
export const updateRule = (id, form) => apiClient.put(`${BASE}/rules/${id}`, form).then(unwrap);
export const setRuleActive = (id, on) =>
  apiClient.post(`${BASE}/rules/${id}/active?on=${on ? 'true' : 'false'}`).then(unwrap);

/** One customer's ledger, newest first — the balance is its sum. */
export const customerPoints = (customerId, size = 25) =>
  apiClient.get(`${BASE}/customers/${customerId}`, { size }).then(unwrap);

/** Moving a balance by hand. The reason is not optional: it goes on the ledger row. */
export const adjustCustomer = (customerId, points, reason) =>
  apiClient.post(`${BASE}/customers/${customerId}/adjust`, { points: Number(points), reason }).then(unwrap);

/** An empty rule, with the exchange rate left blank on purpose. */
export const emptyRule = () => ({
  name: '', description: '',
  earnPointsPer100: '', minOrderToEarn: '',
  pointValue: '', minPointsToRedeem: '', maxRedeemPercent: '',
  expiryMonths: '',
  branchScope: '', branchIds: [],
  customerTypeScope: 'ALL',
  startsAt: '', endsAt: '', isActive: 'Y',
});

const atStart = (d) => (d ? `${d}T00:00:00` : null);
const atEnd = (d) => (d ? `${d}T23:59:59` : null);
const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v));

export function toForm(r) {
  return {
    name: r.name?.trim(),
    description: r.description?.trim() || null,
    earnPointsPer100: numOrNull(r.earnPointsPer100),
    minOrderToEarn: numOrNull(r.minOrderToEarn),
    pointValue: numOrNull(r.pointValue),
    minPointsToRedeem: numOrNull(r.minPointsToRedeem),
    maxRedeemPercent: numOrNull(r.maxRedeemPercent),
    expiryMonths: numOrNull(r.expiryMonths),
    branchScope: r.branchScope,
    branchIds: (r.branchIds || []).map(Number),
    customerTypeScope: r.customerTypeScope || 'ALL',
    startsAt: atStart(r.startsAt),
    endsAt: atEnd(r.endsAt),
    isActive: r.isActive === 'N' ? 'N' : 'Y',
  };
}

export default {
  TIERS, STATE_TONE,
  listRules, getRule, createRule, updateRule, setRuleActive,
  customerPoints, adjustCustomer, emptyRule, toForm,
};
