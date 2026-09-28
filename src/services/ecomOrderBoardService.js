/**
 * E-Commerce Orders board — staff side of docs/ecommerce Phase 8.
 *
 * Every call goes through the staff apiClient (staff JWT) and the server
 * resolves the branch itself, so nothing here passes a branch id: what this
 * screen shows is the session's pharmacy, the way every other board works.
 *
 * The transitions are not this module's opinion. The server holds the state
 * machine, decides which right each move needs, and moves the ERP invoice with
 * it; `ACTIONS` below is only how the buttons are labelled.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ecom/admin/orders';

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

/** The customer-facing statuses, in the order an order passes through them. */
export const STATUSES = ['NEW', 'CONFIRMED', 'PREPARING', 'READY', 'ON_THE_WAY', 'DELIVERED', 'CANCELLED'];

export const STATUS_LABEL = {
  NEW: 'New', CONFIRMED: 'Confirmed', PREPARING: 'Packing', READY: 'Ready',
  ON_THE_WAY: 'On the way', DELIVERED: 'Delivered', CANCELLED: 'Cancelled', RETURNED: 'Returned',
  OPEN: 'Open', ALL: 'All',
};

/** What each move is called on a button, and what it needs before it can run. */
export const ACTIONS = {
  CONFIRM:  { label: 'Confirm',       primary: true,  needsApprove: true },
  PREPARE:  { label: 'Start packing', primary: true },
  READY:    { label: 'Mark ready',    primary: true },
  DISPATCH: { label: 'Dispatch',      primary: true,  needsDriver: true },
  DELIVER:  { label: 'Delivered',     primary: true },
  CANCEL:   { label: 'Cancel',        danger: true,   needsApprove: true, needsReason: true },
};

/** What the ERP invoice is doing while the customer reads the status above. */
export const STAGE_NOTE = {
  NEW: 'The invoice is raised and the stock is already off the shelf.',
  CONFIRMED: 'The invoice is at Confirmed.',
  PREPARING: 'The invoice stays at Confirmed — packing is the shop floor, not the document.',
  READY: 'The invoice stays at Confirmed until it goes out.',
  ON_THE_WAY: 'The invoice is assigned and On The Way.',
  DELIVERED: 'The invoice is Delivered. Collect the money on Sales Activity or Delivery.',
  CANCELLED: 'The invoice is cancelled: stock back on the shelf, journals reversed.',
};

export async function listOrders({ status = 'OPEN', q = '', from = '', to = '', page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (status) params.status = status;
  if (q) params.q = q;
  if (from) params.from = from;
  if (to) params.to = to;
  return unwrapPage(await apiClient.get(BASE, params));
}

export const getStatusCounts = () => apiClient.get(`${BASE}/status-counts`).then(unwrap);
export const getOrder = (orderNo) => apiClient.get(`${BASE}/${orderNo}`).then(unwrap);

/**
 * Moves one order along.
 *
 * @param action one of ACTIONS; DISPATCH needs deliveryManId, CANCEL a reason
 */
export const act = (orderNo, action, { deliveryManId = null, reason = '' } = {}) =>
  apiClient.post(`${BASE}/${orderNo}/action`, {
    action,
    deliveryManId: deliveryManId ? Number(deliveryManId) : undefined,
    reason: reason || undefined,
  }).then(unwrap);

export default {
  STATUSES, STATUS_LABEL, ACTIONS, STAGE_NOTE,
  listOrders, getStatusCounts, getOrder, act,
};
