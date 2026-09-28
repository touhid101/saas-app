/**
 * Topbar alerts.
 *
 * The eight entries this replaces lived in mockData.js and were invented — the
 * same "8 products below alert quantity" for every user, every branch and every
 * day. These are counted from the ledger for the signed-in user's branch.
 *
 * The server also says whether the caller is entitled to a bell at all, because
 * "no right" and "nothing wrong today" are different states that must not look
 * the same on screen.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/notifications';

/**
 * Alerts for the signed-in user.
 *
 * Returns { permitted, items }. `permitted` is the View right on Notifications
 * in RBAC; without it there is no bell at all. With it and an empty `items`,
 * there is a bell and nothing is wrong — a different thing, drawn differently.
 *
 * Never throws: a failed bell must not break the page around it.
 */
export async function getNotifications() {
  try {
    const res = await apiClient.get(BASE);
    return {
      permitted: !!res?.permitted,
      items: Array.isArray(res?.items) ? res.items : [],
    };
  } catch (err) {
    // Not worth a toast or a console error on every page load.
    console.debug('[notifications] unavailable:', err?.message);
    return { permitted: false, items: [] };
  }
}

/* ── Read state ────────────────────────────────────────────────────────────
   Alerts are computed, not stored: there is no row to mark read, and a table
   for it would be a table of dismissals for figures that change hourly.

   So it is remembered per browser, keyed by the alert and the count that
   raised it. Dismissing "3 products have no stock left" silences that alert
   until the count moves — at which point it is genuinely new information and
   comes back. A key of the id alone would silence it for ever; no key at all
   would make the tick meaningless. */

const STORE = 'pharma_notifications_read';

function readMap() {
  try { return JSON.parse(localStorage.getItem(STORE)) || {}; }
  catch { return {}; }
}

function writeMap(map) {
  try { localStorage.setItem(STORE, JSON.stringify(map)); } catch { /* private mode */ }
}

/** Stamps each alert with whether this browser has seen it at this count. */
export function applyReadState(rows) {
  const seen = readMap();
  return rows.map(n => ({ ...n, read: seen[n.id] === n.count }));
}

export function markRead(id, count) {
  const map = readMap();
  map[id] = count;
  writeMap(map);
}

export function markAllRead(rows) {
  const map = readMap();
  rows.forEach(n => { map[n.id] = n.count; });
  writeMap(map);
}

export default { getNotifications, applyReadState, markRead, markAllRead };
