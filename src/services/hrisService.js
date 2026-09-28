/**
 * hrisService.js — employee self-service: my HR, my requests, my forms.
 *
 * Every call is about the signed-in user. There is no employeeId anywhere in
 * these paths — the server resolves the person from the login — so nothing
 * here can be pointed at somebody else by editing a parameter.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/v1/hr/me';
/* apiClient already returns the envelope's data. It is not unwrapped a second
   time here: a request detail carries a `data` field of its own — the
   module's record — and a generic "take .data if present" would swallow the
   detail and hand back the record alone. */
const unwrap = (r) => r;
const unwrapPage = (r) => {
  const d = unwrap(r) || {};
  return {
    items: d.content ?? d.items ?? [],
    page: d.page ?? d.number ?? 0,
    size: d.size ?? 20,
    total: d.totalElements ?? d.total ?? 0,
    totalPages: d.totalPages ?? 1,
  };
};
const clean = (o) => Object.fromEntries(Object.entries(o || {}).filter(([, v]) => v !== '' && v != null));

export const REQUEST_TYPES = [
  { id: 'LEAVE',      label: 'Leave' },
  { id: 'EXPENSE',    label: 'Expense' },
  { id: 'ADVANCE',    label: 'Advance' },
  { id: 'LOAN',       label: 'Loan' },
  { id: 'ATTENDANCE', label: 'Attendance' },
];
export const REQUEST_STATUSES = ['DRAFT', 'PENDING', 'APPROVED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'CANCELLED'];

/* ── Reads ── */
export const getMe        = () => apiClient.get(BASE).then(unwrap);
export const getSummary   = () => apiClient.get(`${BASE}/summary`).then(unwrap);
export const getRequests  = (params = {}) =>
  apiClient.get(`${BASE}/requests`, clean({ page: 0, size: 20, ...params })).then(unwrapPage);
export const getRequest   = (type, id) => apiClient.get(`${BASE}/requests/${type}/${id}`).then(unwrap);
export const getAttendance = (month) => apiClient.get(`${BASE}/attendance`, month ? { month } : null).then(unwrap);
export const getCalendar  = (month) => apiClient.get(`${BASE}/calendar`, month ? { month } : null).then(unwrap);
export const getLeaveBalances = () => apiClient.get(`${BASE}/leave/balances`).then(unwrap);
export const getLeaveTypes    = () => apiClient.get(`${BASE}/leave/types`).then(unwrap);
export const getExpenseTypes  = () => apiClient.get(`${BASE}/expenses/types`).then(unwrap);

/* ── Writes — never a decision ── */
export const applyLeave     = (body) => apiClient.post(`${BASE}/leave`, body).then(unwrap);
export const fileExpense    = (body) => apiClient.post(`${BASE}/expenses`, body).then(unwrap);
export const submitExpense  = (claimId) => apiClient.post(`${BASE}/expenses/${claimId}/submit`).then(unwrap);
export const attachReceipt  = (lineId, file) => {
  const form = new FormData();
  form.append('file', file);
  return apiClient.upload(`${BASE}/expenses/lines/${lineId}/receipt`, form).then(unwrap);
};
export const removeReceipt  = (lineId) => apiClient.delete(`${BASE}/expenses/lines/${lineId}/receipt`).then(unwrap);
export const requestAdvance = (body) => apiClient.post(`${BASE}/advances`, body).then(unwrap);
export const requestLoan    = (body) => apiClient.post(`${BASE}/loans`, body).then(unwrap);
export const requestCorrection = (attendanceId, body) =>
  apiClient.post(`${BASE}/attendance/${attendanceId}/correction`, body).then(unwrap);
export const cancelRequest  = (type, id, reason) =>
  apiClient.post(`${BASE}/requests/${type}/${id}/cancel`, { reason: reason || null }).then(unwrap);
