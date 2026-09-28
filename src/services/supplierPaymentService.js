/**
 * Supplier payments — money out, what is still owed, and how each supplier
 * performs.
 *
 * One thing worth knowing before editing: none of the outstanding arithmetic
 * happens here or on the server's service layer. It lives in the database views
 * V_PURCHASE_PAYABLE and V_SUPPLIER_ACCOUNT, which the reports read too. That is
 * deliberate — the figure on this screen and the figure in Supplier Outstanding
 * have to be the same number, and the only reliable way to guarantee that is for
 * them to be literally the same query.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/supplier-payments';

/** How money actually leaves the business here. */
export const PAYMENT_MODES = ['CASH', 'BANK', 'CHEQUE', 'BKASH', 'NAGAD', 'ROCKET', 'ADJUSTMENT'];

/** Modes that need a cheque number and a bank to be meaningful. */
export const CHEQUE_MODES = ['CHEQUE'];
export const BANK_MODES = ['BANK', 'CHEQUE'];

const num = (v) => (v == null ? 0 : Number(v));
/** Null stays null — "no limit recorded" is not "a limit of zero". */
const maybe = (v) => (v == null ? null : Number(v));

/* ═══════════════════════════════════════════════════════════════════════════
   WHAT IS OWED
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getOutstanding({ q = '', dueOnly = false, includeDormant = false } = {}) {
  const params = { dueOnly, includeDormant };
  if (q && q.trim()) params.q = q.trim();
  const rows = await apiClient.get(`${BASE}/outstanding`, params);
  return (rows ?? []).map(toAccount);
}

export async function getSupplier(supplierId) {
  const d = await apiClient.get(`${BASE}/supplier/${supplierId}`);
  return {
    account: d?.account ? toAccount(d.account) : null,
    openInvoices: (d?.openInvoices ?? []).map(toPayable),
    recentPayments: (d?.recentPayments ?? []).map(toPayment),
    advance: d?.advance ?? null,
  };
}

export async function getInvoices(supplierId, { openOnly = true } = {}) {
  const rows = await apiClient.get(`${BASE}/supplier/${supplierId}/invoices`, { openOnly });
  return (rows ?? []).map(toPayable);
}

export async function getStatement(supplierId, { from = '', to = '' } = {}) {
  const params = {};
  if (from) params.from = from;
  if (to) params.to = to;
  const rows = await apiClient.get(`${BASE}/supplier/${supplierId}/statement`, params);
  return (rows ?? []).map((l) => ({
    date: l.date, kind: l.kind, docNo: l.docNo,
    debit: num(l.debit), credit: num(l.credit),
    mode: l.mode, note: l.note, balance: num(l.balance),
  }));
}

/* ═══════════════════════════════════════════════════════════════════════════
   PAYING
   ═══════════════════════════════════════════════════════════════════════════ */

export async function pay({
  supplierId, purchaseId, paymentDate, amount, paymentMode,
  advanceAmount, payInvoiceDiscount,
  bankName, chequeNo, chequeDate, referenceNo, remarks,
}) {
  return toPayment(await apiClient.post(BASE, {
    supplierId,
    // Undefined rather than null: omitting the field is what tells the server
    // this is an on-account payment, and an explicit null reads the same but
    // relies on the deserialiser treating them alike.
    purchaseId: purchaseId || undefined,
    paymentDate: paymentDate || undefined,
    amount: Number(amount),
    paymentMode,
    // Sent only when there is one. A zero advance and no advance are the same
    // thing to the server, but sending 0 alongside the discount flag reads as
    // a contradiction it would have to resolve.
    advanceAmount: Number(advanceAmount) > 0 ? Number(advanceAmount) : undefined,
    payInvoiceDiscount: payInvoiceDiscount || undefined,
    bankName: bankName || undefined,
    chequeNo: chequeNo || undefined,
    chequeDate: chequeDate || undefined,
    referenceNo: referenceNo || undefined,
    remarks: remarks || undefined,
  }));
}

/**
 * Advances suppliers hold of ours — money paid beyond the bills.
 *
 * Netted per supplier rather than per voucher: a supplier returns a sum, not a
 * particular payment, so pretending the recovery maps to one would be an
 * allocation nobody made.
 */
export async function getAdvances({ supplierId = null, openOnly = true } = {}) {
  const params = { openOnly };
  if (supplierId) params.supplierId = supplierId;
  const rows = await apiClient.get(`${BASE}/advances`, params);
  return (rows ?? []).map((a) => ({
    supplierId: a.supplierId,
    supplierName: a.supplierName || '—',
    advanced: num(a.advanced),
    recovered: num(a.recovered),
    balance: num(a.balance),
    firstAdvance: a.firstAdvance,
    lastMovement: a.lastMovement,
  }));
}

/**
 * The supplier giving an advance back.
 *
 * Two shapes, and they are not the same event: money returning (leave
 * purchaseId null) or the advance being taken as credit on a later bill
 * (paymentMode ADJUSTMENT plus the purchase it credits).
 */
export async function recoverAdvance({
  supplierId, purchaseId, recoveryDate, amount, paymentMode,
  bankName, chequeNo, chequeDate, referenceNo, remarks,
}) {
  return toPayment(await apiClient.post(`${BASE}/advances/recover`, {
    supplierId,
    purchaseId: purchaseId || undefined,
    recoveryDate: recoveryDate || undefined,
    amount: Number(amount),
    paymentMode,
    bankName: bankName || undefined,
    chequeNo: chequeNo || undefined,
    chequeDate: chequeDate || undefined,
    referenceNo: referenceNo || undefined,
    remarks: remarks || undefined,
  }));
}

export async function cancelPayment(id, reason) {
  return toPayment(await apiClient.post(`${BASE}/${id}/cancel`, { reason }));
}

export async function listPayments({
  supplierId = null, from = '', to = '', mode = '', q = '',
  includeCancelled = false, page = 0, size = 25,
} = {}) {
  const params = { page, size, includeCancelled };
  if (supplierId) params.supplierId = supplierId;
  if (from) params.from = from;
  if (to) params.to = to;
  if (mode && mode !== 'All') params.mode = mode;
  if (q && q.trim()) params.q = q.trim();
  const body = apiClient.unwrapPagination(await apiClient.get(BASE, params));
  return { ...body, items: (body.items ?? []).map(toPayment) };
}

/* ═══════════════════════════════════════════════════════════════════════════
   PERFORMANCE
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getPerformance({ from = '', to = '', q = '' } = {}) {
  const params = {};
  if (from) params.from = from;
  if (to) params.to = to;
  if (q && q.trim()) params.q = q.trim();
  const rows = await apiClient.get(`${BASE}/performance`, params);
  return (rows ?? []).map((p) => ({
    supplierId: p.supplierId,
    supplierName: p.supplierName,
    mobileNo: p.mobileNo,
    creditLimit: maybe(p.creditLimit),
    invoices: num(p.invoices),
    billed: num(p.billed),
    paid: num(p.paid),
    due: num(p.due),
    returnCount: num(p.returnCount),
    returned: num(p.returned),
    returnPct: num(p.returnPct),
    // Null, not zero. Nothing paid against a named invoice in the window is a
    // different fact from paying on the day the bill was raised.
    avgDaysToPay: maybe(p.avgDaysToPay),
    lastPurchase: p.lastPurchase,
  }));
}

/* ═══════════════════════════════════════════════════════════════════════════
   SHAPES
   ═══════════════════════════════════════════════════════════════════════════ */

function toAccount(a) {
  return {
    supplierId: a.supplierId,
    supplierName: a.supplierName || '—',
    mobileNo: a.mobileNo,
    responsiblePerson: a.responsiblePerson,
    creditLimit: maybe(a.creditLimit),
    openingBal: num(a.openingBal),
    invoices: num(a.invoices),
    billed: num(a.billed),
    paid: num(a.paid),
    returnCredit: num(a.returnCredit),
    // Return credit not tied to any invoice — already off outstanding.
    unappliedCredit: num(a.unappliedCredit),
    // Paid but not matched to a bill. Kept apart from the ageing buckets so
    // the arithmetic on screen stays checkable: buckets - unapplied = outstanding.
    onAccount: num(a.onAccount),
    // Our money the supplier still holds. The one figure here that runs the
    // other way, so it is never folded into the outstanding figure.
    advanceBalance: num(a.advanceBalance),
    outstanding: num(a.outstanding),
    netPosition: num(a.netPosition),
    ageing: {
      d0: num(a.due0To30), d31: num(a.due31To60),
      d61: num(a.due61To90), d90: num(a.due90Plus),
    },
    firstPurchase: a.firstPurchase,
    lastPurchase: a.lastPurchase,
    lastPaymentDate: a.lastPaymentDate,
    lastPaymentAmount: maybe(a.lastPaymentAmount),
    creditUsedPct: maybe(a.creditUsedPct),
    creditWarning: a.creditWarning ?? null,
  };
}

function toPayable(p) {
  return {
    purchaseId: p.purchaseId,
    invoiceNo: p.invoiceNo,
    transactionDate: p.transactionDate,
    paymentType: p.paymentType,
    billed: num(p.billed),
    paidAtInvoice: num(p.paidAtInvoice),
    paidLater: num(p.paidLater),
    returnCredit: num(p.returnCredit),
    due: num(p.due),
    ageDays: num(p.ageDays),
    invoiceDiscAmt: num(p.invoiceDiscAmt),
  };
}

function toPayment(p) {
  return {
    id: p.id,
    voucherNo: p.voucherNo,
    paymentDate: p.paymentDate,
    supplierId: p.supplierId,
    supplierName: p.supplierName || '—',
    purchaseId: p.purchaseId ?? null,
    purchaseInvoiceNo: p.purchaseInvoiceNo ?? null,
    amount: num(p.amount),
    advanceAmount: num(p.advanceAmount),
    // INVOICE rows mirror the cash keyed on the purchase; they have no voucher
    // of their own and cannot be reversed from this screen.
    fromInvoice: p.source === 'INVOICE',
    isRecovery: p.entryType === 'ADVANCE_RECOVERY',
    paymentMode: p.paymentMode,
    bankName: p.bankName,
    chequeNo: p.chequeNo,
    chequeDate: p.chequeDate,
    referenceNo: p.referenceNo,
    remarks: p.remarks,
    cancelled: p.status === 'C',
    cancelReason: p.cancelReason,
  };
}

export default {
  getOutstanding, getSupplier, getInvoices, getStatement,
  pay, cancelPayment, listPayments, getPerformance,
  getAdvances, recoverAdvance,
  PAYMENT_MODES, CHEQUE_MODES, BANK_MODES,
};
