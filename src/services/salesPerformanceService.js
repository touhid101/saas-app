/**
 * Sales performance.
 *
 * Counted from the ledger for the signed-in user's branch. The screen this
 * serves used to build eight salespeople, their targets and their commission
 * from a seeded random number generator in mockData.js.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/sales-performance';

/** ISO first-of-month for a "YYYY-MM" value, which is what <input type="month"> gives. */
export const monthToIso = (ym) => (ym ? `${ym}-01` : null);

/** "YYYY-MM" for an ISO date, for the month input. */
export const isoToMonth = (iso) => (iso ? String(iso).slice(0, 7) : '');

/** The current month, as the input wants it. */
export function thisMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Performance for one month. `month` is "YYYY-MM". */
export async function getPerformance(month) {
  const iso = monthToIso(month);
  return apiClient.get(BASE, iso ? { month: iso } : null);
}

/**
 * Set or clear one person's target.
 *
 * An amount of 0 or null clears it — "not set" and "asked for nothing" are
 * different, and the table draws them differently.
 *
 * Returns the whole month back, so the screen never recomputes totals for
 * itself and reaches a different answer from the server.
 */
export async function setTarget({ userId, month, targetAmount, commissionPct }) {
  return apiClient.put(`${BASE}/target`, {
    userId,
    month: monthToIso(month),
    targetAmount: targetAmount === '' || targetAmount == null ? null : Number(targetAmount),
    commissionPct: commissionPct === '' || commissionPct == null ? null : Number(commissionPct),
  });
}

export default { getPerformance, setTarget, thisMonth, monthToIso, isoToMonth };
