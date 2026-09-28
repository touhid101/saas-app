/**
 * E-Commerce Customers console — staff side of docs/ecommerce Phase 1.
 *
 * Every call goes through the staff apiClient (staff JWT). The server insists
 * on a platform operator with MOD55 rights for all of them; this module only
 * shapes the wire.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ecom/admin/customers';

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

export const STATUSES = ['PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED'];
export const TYPES = ['RETAILER', 'WHOLESALER', 'SPECIAL'];

export const STATUS_LABEL = {
  PENDING: 'Pending', APPROVED: 'Approved', SUSPENDED: 'Suspended', REJECTED: 'Rejected',
};
export const TYPE_LABEL = { RETAILER: 'Retailer', WHOLESALER: 'Wholesaler', SPECIAL: 'Special' };

export async function listCustomers({ status = '', type = '', q = '', page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (status) params.status = status;
  if (type) params.type = type;
  if (q) params.q = q;
  return unwrapPage(await apiClient.get(BASE, params));
}

export const getStatusCounts = () => apiClient.get(`${BASE}/status-counts`).then(unwrap);
export const getCustomer     = (id) => apiClient.get(`${BASE}/${id}`).then(unwrap);
export const approve  = (id, body = {}) => apiClient.post(`${BASE}/${id}/approve`, body).then(unwrap);
export const reject   = (id, reason)    => apiClient.post(`${BASE}/${id}/reject`, { reason }).then(unwrap);
export const suspend  = (id, reason)    => apiClient.post(`${BASE}/${id}/suspend`, { reason }).then(unwrap);
export const setType  = (id, customerType, reason) => apiClient.post(`${BASE}/${id}/type`, { customerType, reason }).then(unwrap);
/* [V112] The region a wholesale account may buy in. A null regionId clears it,
   which stops wholesale ordering until one is approved again. */
export const setRegion = (id, regionId, reason) => apiClient.post(`${BASE}/${id}/region`, { regionId: regionId || null, reason }).then(unwrap);
export const unlock   = (id) => apiClient.post(`${BASE}/${id}/unlock`).then(unwrap);
export const linkBranch = (id, branchId) => apiClient.post(`${BASE}/${id}/branches/${branchId}/link`).then(unwrap);
/** Every pharmacy on the platform, for the link picker (platform operators only). */
export const listPharmacies = () => apiClient.get('/api/admin/subscriptions').then((r) => (unwrap(r) ?? []).map((t) => ({ branchId: t.branchId, branchName: t.branchName })));

export default {
  STATUSES, TYPES, STATUS_LABEL, TYPE_LABEL,
  listCustomers, getStatusCounts, getCustomer, approve, reject, suspend, setType, setRegion, unlock, linkBranch, listPharmacies,
};
