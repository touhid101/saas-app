/**
 * The pharmacy's subscription.
 *
 * Nothing here works out whether access has run out — the server decides that,
 * with the same code the API gate uses, and sends `allowed`, `daysRemaining` and
 * `inGrace` already resolved. A screen that counted the days itself would
 * eventually disagree with the gate, and the day it did the badge would say
 * "12 days left" over a locked-out application.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/subscription';
const ADMIN = '/api/admin/subscriptions';

const num = (v) => (v == null ? 0 : Number(v));

function shapeMine(d) {
  if (!d) return null;
  return {
    branchId: d.branchId ?? null,
    branchName: d.branchName ?? '—',
    tenantStatus: d.tenantStatus ?? 'UNKNOWN',
    allowed: !!d.allowed,
    reason: d.reason ?? null,
    planCode: d.planCode ?? null,
    planName: d.planName ?? null,
    price: num(d.price),
    billingTerm: d.billingTerm ?? 'MONTHLY',
    startsOn: d.startsOn ?? null,
    expiresOn: d.expiresOn ?? null,
    lastDayOfAccess: d.lastDayOfAccess ?? null,
    graceDays: d.graceDays ?? 0,
    daysRemaining: num(d.daysRemaining),
    inGrace: !!d.inGrace,
    renewalDue: !!d.renewalDue,
    outstanding: (d.outstanding ?? []).map(shapeInvoice),
  };
}

function shapeInvoice(i) {
  return {
    invoiceId: i.invoiceId,
    invoiceNo: i.invoiceNo,
    periodFrom: i.periodFrom,
    periodTo: i.periodTo,
    amount: num(i.amount),
    issuedOn: i.issuedOn,
    dueOn: i.dueOn,
    payStatus: i.payStatus,
  };
}

export async function getMine() {
  return shapeMine(await apiClient.get(`${BASE}/mine`));
}

export async function getPlans() {
  const rows = await apiClient.get(`${BASE}/plans`);
  return (rows ?? []).map((p) => ({ ...p, price: num(p.price) }));
}

/** Raises the next period's invoice. Clicking twice owes one month, not two. */
export async function raiseRenewal(planId) {
  return shapeInvoice(await apiClient.post(`${BASE}/pay/renew`, planId ? { planId } : {}));
}

/* ── Super Admin ── */

export async function getTenants() {
  const rows = await apiClient.get(ADMIN);
  return (rows ?? []).map((t) => ({ ...t, outstandingAmount: num(t.outstandingAmount) }));
}

export async function raiseFor(branchId, planId) {
  return shapeInvoice(await apiClient.post(`${ADMIN}/${branchId}/invoice`, planId ? { planId } : {}));
}

/**
 * Records money against an invoice.
 *
 * `idempotencyKey` is generated per attempt so that a double-click, or a retry
 * after a dropped connection, settles the invoice once. The same guard is what a
 * payment gateway's webhook will use.
 */
export async function recordPayment(invoiceId, { amount, method = 'MANUAL', providerRef, note } = {}) {
  return apiClient.post(`${ADMIN}/invoices/${invoiceId}/payment`, {
    amount, method, providerRef, note,
    idempotencyKey: `ui-${invoiceId}-${Date.now()}`,
  });
}

export async function setTenantStatus(branchId, status, reason) {
  return apiClient.patch(`${ADMIN}/${branchId}/status`, { status, reason });
}

export async function getAudit(branchId) {
  return (await apiClient.get(`${ADMIN}/${branchId}/audit`)) ?? [];
}

export default { getMine, getPlans, raiseRenewal, getTenants, raiseFor, recordPayment, setTenantStatus, getAudit };
