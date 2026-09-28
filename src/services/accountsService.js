/**
 * accountsService.js — the Accounts module's API surface.
 * Every call goes through apiClient (auth, envelope, errors) and returns the
 * envelope's data. Page lists come back as { items, total, totalPages }.
 */
import apiClient from '../lib/apiClient.js';

const B = '/api/v1/accounts';
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);
const page = (r) => { const d = unwrap(r) || {}; return { items: d.content || [], total: d.totalElements ?? 0, totalPages: d.totalPages ?? 1, page: d.page ?? 0 }; };
const qs = (params = {}) => {
  const p = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  return p.length ? `?${p.join('&')}` : '';
};

/* ── dashboard, chart, settings, periods ── */
export const getDashboard  = (params) => apiClient.get(`${B}/dashboard${qs(params)}`).then(unwrap);
export const getChart      = () => apiClient.get(`${B}/chart`).then(unwrap);
export const createAccount = (body) => apiClient.post(`${B}/chart`, body).then(unwrap);
export const updateAccount = (id, body) => apiClient.put(`${B}/chart/${id}`, body).then(unwrap);
export const getPeriods    = (yearId) => apiClient.get(`${B}/periods${qs({ yearId })}`).then(unwrap);
export const setPeriodStatus = (id, status) => apiClient.post(`${B}/periods/${id}/status`, { status }).then(unwrap);
export const createYear    = (date) => apiClient.post(`${B}/periods/years`, { date }).then(unwrap);
export const getSettings   = () => apiClient.get(`${B}/settings`).then(unwrap);
export const saveSettings  = (body) => apiClient.put(`${B}/settings`, body).then(unwrap);
export const savePaymentMode = (body) => apiClient.post(`${B}/settings/payment-modes`, body).then(unwrap);
export const createBank    = (body) => apiClient.post(`${B}/settings/bank-accounts`, body).then(unwrap);
export const updateBank    = (id, body) => apiClient.put(`${B}/settings/bank-accounts/${id}`, body).then(unwrap);
export const saveTaxRate   = (body) => apiClient.post(`${B}/settings/tax-rates`, body).then(unwrap);
/* The short list an entry screen offers, active only. Separate from
   getSettings, which carries periods, banks, tax rates and rules too. */
export const getCostCenters = () => apiClient.get(`${B}/cost-centers`).then(unwrap);
export const saveCostCenter = (body) => apiClient.post(`${B}/settings/cost-centers`, body).then(unwrap);
export const saveRule      = (eventType, body) => apiClient.post(`${B}/settings/rules/${eventType}`, body).then(unwrap);

/* ── journals & ledger ── */
export const searchJournals = (params) => apiClient.get(`${B}/journals${qs(params)}`).then(page);
export const getJournal     = (id) => apiClient.get(`${B}/journals/${id}`).then(unwrap);
export const createJournal  = (body) => apiClient.post(`${B}/journals`, body).then(unwrap);
export const updateJournal  = (id, body) => apiClient.put(`${B}/journals/${id}`, body).then(unwrap);
export const discardJournal = (id) => apiClient.delete(`${B}/journals/${id}`).then(unwrap);
export const postJournal    = (id) => apiClient.post(`${B}/journals/${id}/post`, {}).then(unwrap);
export const reverseJournal = (id, reason, date) => apiClient.post(`${B}/journals/${id}/reverse`, { reason, date }).then(unwrap);
export const getLedger      = (params) => apiClient.get(`${B}/ledger${qs(params)}`).then(unwrap);
export const getTrialBalance = (params) => apiClient.get(`${B}/trial-balance${qs(params)}`).then(unwrap);
export const getParties     = (partyType, params) => apiClient.get(`${B}/parties/${partyType}${qs(params)}`).then(unwrap);
export const getStatement   = (partyType, partyId, params) => apiClient.get(`${B}/parties/${partyType}/${partyId}/statement${qs(params)}`).then(unwrap);

/* ── cash & bank, daily cash ── */
export const getCashPositions = (params) => apiClient.get(`${B}/cash/positions${qs(params)}`).then(unwrap);
export const getCashAccounts  = () => apiClient.get(`${B}/cash/accounts`).then(unwrap);
export const searchVouchers   = (params) => apiClient.get(`${B}/cash/vouchers${qs(params)}`).then(page);
export const createVoucher    = (body) => apiClient.post(`${B}/cash/vouchers`, body).then(unwrap);
export const approveVoucher   = (id) => apiClient.post(`${B}/cash/vouchers/${id}/approve`, {}).then(unwrap);
export const cancelVoucher    = (id, reason) => apiClient.post(`${B}/cash/vouchers/${id}/cancel`, { reason }).then(unwrap);
export const getDaySheet      = (params) => apiClient.get(`${B}/daily-cash/current${qs(params)}`).then(unwrap);
export const getCashBook      = (params) => apiClient.get(`${B}/daily-cash/book${qs(params)}`).then(unwrap);
export const openDay          = (body = {}) => apiClient.post(`${B}/daily-cash/open`, body).then(unwrap);
export const countDay         = (dayId, body) => apiClient.post(`${B}/daily-cash/${dayId}/count`, body).then(unwrap);
export const closeDay         = (dayId, body = {}) => apiClient.post(`${B}/daily-cash/${dayId}/close`, body).then(unwrap);
export const reopenDay        = (dayId, reason) => apiClient.post(`${B}/daily-cash/${dayId}/reopen`, { reason }).then(unwrap);

/* ── investors & profit ── */
export const getInvestors      = () => apiClient.get(`${B}/investors`).then(unwrap);
export const saveInvestor      = (id, body) => (id ? apiClient.put(`${B}/investors/${id}`, body) : apiClient.post(`${B}/investors`, body)).then(unwrap);
export const getInvestor       = (id, params) => apiClient.get(`${B}/investors/${id}${qs(params)}`).then(unwrap);
export const searchInvestorTxns = (params) => apiClient.get(`${B}/investor-transactions${qs(params)}`).then(page);
export const createInvestorTxn = (body) => apiClient.post(`${B}/investor-transactions`, body).then(unwrap);
export const approveInvestorTxn = (id) => apiClient.post(`${B}/investor-transactions/${id}/approve`, {}).then(unwrap);
export const cancelInvestorTxn = (id, reason) => apiClient.post(`${B}/investor-transactions/${id}/cancel`, { reason }).then(unwrap);
export const getPolicies       = () => apiClient.get(`${B}/profit/policies`).then(unwrap);
export const savePolicy        = (body) => apiClient.post(`${B}/profit/policies`, body).then(unwrap);
export const getDistributions  = () => apiClient.get(`${B}/profit/distributions`).then(unwrap);
export const getDistribution   = (id) => apiClient.get(`${B}/profit/distributions/${id}`).then(unwrap);
export const calculateProfit   = (body) => apiClient.post(`${B}/profit/distributions/calculate`, body).then(unwrap);
export const distributionStep  = (id, step, body = {}) => apiClient.post(`${B}/profit/distributions/${id}/${step}`, body).then(unwrap);

/* ── reconciliation ── */
export const getEvents         = (params) => apiClient.get(`${B}/events${qs(params)}`).then(page);
export const retryEvent        = (id) => apiClient.post(`${B}/events/${id}/retry`, {}).then(unwrap);
export const getReconciliation = (params) => apiClient.get(`${B}/reconciliation${qs(params)}`).then(unwrap);
export const runBackfill       = (body) => apiClient.post(`${B}/backfill`, body).then(unwrap);
export const getBackfillBatches = () => apiClient.get(`${B}/backfill/batches`).then(unwrap);

/* ── vocabulary ── */
export const ACCOUNT_TYPES = ['ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'];
export const VOUCHER_KINDS = [
  { id: 'RECEIPT', label: 'Receipt — money in' }, { id: 'PAYMENT', label: 'Payment — money out' },
  { id: 'TRANSFER', label: 'Transfer — between cash accounts' }, { id: 'OPENING', label: 'Opening balance (first day)' },
  { id: 'ADJUSTMENT_IN', label: 'Adjustment in (approved)' }, { id: 'ADJUSTMENT_OUT', label: 'Adjustment out (approved)' },
];
export const INVESTOR_TXN_TYPES = [
  { id: 'INITIAL_INVESTMENT', label: 'Initial investment' }, { id: 'ADDITIONAL_INVESTMENT', label: 'Additional investment' },
  { id: 'CAPITAL_WITHDRAWAL', label: 'Capital withdrawal' }, { id: 'CAPITAL_ADJUSTMENT', label: 'Capital adjustment (approved)' },
  { id: 'REFUND', label: 'Refund' }, { id: 'PROFIT_DISTRIBUTION', label: 'Profit payment' },
];
export const SHARE_METHODS = [
  { id: 'CAPITAL_RATIO', label: 'Capital ratio — by closing capital' },
  { id: 'WEIGHTED_AVERAGE_CAPITAL', label: 'Weighted average capital over the period' },
  { id: 'INVESTOR_SPECIFIC_PERCENTAGE', label: 'Investor-specific percentages' },
  { id: 'FIXED_PERCENTAGE', label: 'Fixed pool % to investors, split by capital ratio' },
  { id: 'CUSTOM_APPROVED_METHOD', label: 'Custom — each investor’s own share %' },
];
export const PAYMENT_MODES = ['CASH', 'BANK', 'CARD', 'CHEQUE', 'BKASH', 'NAGAD', 'ROCKET'];
