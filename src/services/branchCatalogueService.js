/**
 * The medicine catalogue, as a pharmacy uses it.
 *
 * The operator's screen at /api/admin/global-products maintains the catalogue
 * and pushes entries to chosen pharmacies. This is the pull side: read the same
 * catalogue, copy what you want into your own product list.
 *
 * No branch is ever sent. The server imports into the branch on the session, so
 * this cannot write into anybody else's pharmacy however the request is shaped.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/catalogue';

/** One page of the catalogue. */
export async function search({ q = '', company = '', page = 0, size = 50 } = {}) {
  return apiClient.get(BASE, { q, company, page, size });
}

/** Manufacturers, for the filter. */
export async function companies() {
  try {
    const list = await apiClient.get(`${BASE}/companies`);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

/**
 * Which pharmacy an import would land in, and whether it may be run.
 *
 * Read from the server rather than from the signed-in user, so the name on
 * screen and the branch actually written are the same answer.
 */
export async function target() {
  return apiClient.get(`${BASE}/target`);
}

/**
 * Copies entries into this pharmacy.
 *
 * An empty list means everything matching the search that is passed with it —
 * every page of it, not the rows on screen. Somebody who ticked nothing and
 * pressed Import meant all of it, and "all of it" means all of what they are
 * looking at: the filter travels so the count on the button and the rows
 * written come from one query.
 */
export async function importHere(productIds = [], { q = '', company = '' } = {}) {
  return apiClient.post(`${BASE}/import`, { productIds, q, company });
}

export default { search, companies, target, importHere };
