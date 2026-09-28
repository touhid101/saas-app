/**
 * E-Commerce Promotions (MOD54) — coupon codes, staff side.
 *
 * docs/ecommerce Phase 12. The server decides who may write which coupon — a
 * pharmacy for its own branch, the platform for every branch — and refuses in
 * words, so this module only shapes the wire.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ecom/admin/coupons';

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

export const DISCOUNT_TYPES = [
  { value: 'PERCENT', label: 'Percentage off' },
  { value: 'FIXED', label: 'Fixed amount off' },
  { value: 'FREE_DELIVERY', label: 'Free delivery' },
];

export const TIERS = [
  { value: 'ALL', label: 'Any customer' },
  { value: 'RETAILER', label: 'Retailers only' },
  { value: 'WHOLESALER', label: 'Wholesalers only' },
  { value: 'SPECIAL', label: 'Special accounts only' },
];

/** Running · Scheduled · Expired · Taken up · Stopped — the server decides which. */
export const STATE_TONE = {
  Running: 'sage', Scheduled: 'teal', Expired: 'grey', 'Taken up': 'amber', Stopped: 'clay',
};

export async function listCoupons({ active = '', q = '', page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (active) params.active = active;
  if (q) params.q = q;
  return unwrapPage(await apiClient.get(BASE, params));
}

export const getCoupon = (id) => apiClient.get(`${BASE}/${id}`).then(unwrap);
export const createCoupon = (form) => apiClient.post(BASE, form).then(unwrap);
export const updateCoupon = (id, form) => apiClient.put(`${BASE}/${id}`, form).then(unwrap);
export const setActive = (id, on) => apiClient.post(`${BASE}/${id}/active?on=${on ? 'true' : 'false'}`).then(unwrap);

/** An empty coupon, with the two decisions left blank on purpose. */
export const emptyCoupon = () => ({
  code: '', name: '', description: '',
  discountType: 'PERCENT', discountValue: '', maxDiscountAmount: '', minOrderAmount: '',
  branchScope: '', branchIds: [],
  customerTypeScope: 'ALL', firstOrderOnly: 'N',
  usageLimitTotal: '', usageLimitPerCustomer: '',
  startsAt: '', endsAt: '', isActive: 'Y',
});

/** A date box gives "2026-10-01"; the server wants a moment. */
const atStart = (d) => (d ? `${d}T00:00:00` : null);
const atEnd = (d) => (d ? `${d}T23:59:59` : null);

const numOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v));

export function toForm(c) {
  return {
    code: c.code?.trim(),
    name: c.name?.trim(),
    description: c.description?.trim() || null,
    discountType: c.discountType,
    discountValue: c.discountType === 'FREE_DELIVERY' ? null : numOrNull(c.discountValue),
    maxDiscountAmount: c.discountType === 'PERCENT' ? numOrNull(c.maxDiscountAmount) : null,
    minOrderAmount: numOrNull(c.minOrderAmount),
    branchScope: c.branchScope,
    branchIds: (c.branchIds || []).map(Number),
    customerTypeScope: c.customerTypeScope || 'ALL',
    firstOrderOnly: c.firstOrderOnly === 'Y' ? 'Y' : 'N',
    usageLimitTotal: numOrNull(c.usageLimitTotal),
    usageLimitPerCustomer: numOrNull(c.usageLimitPerCustomer),
    startsAt: atStart(c.startsAt),
    endsAt: atEnd(c.endsAt),
    isActive: c.isActive === 'N' ? 'N' : 'Y',
  };
}

export default {
  DISCOUNT_TYPES, TIERS, STATE_TONE,
  listCoupons, getCoupon, createCoupon, updateCoupon, setActive, emptyCoupon, toForm,
};
