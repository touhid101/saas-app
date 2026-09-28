/**
 * Global search.
 *
 * Results come from the server, scoped to the signed-in user's branch and to
 * the modules they may already view. The palette this replaces filtered the
 * mockData seed arrays in the browser and never called anything.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/v1/search';

/**
 * Search this branch.
 *
 * Returns { items, failed }. `failed` is reported rather than swallowed: the
 * endpoint this replaces wrapped every query in a catch that returned an empty
 * list, so five queries written against the wrong column names looked exactly
 * like "nothing matched" and went unnoticed. The palette says which happened.
 */
export async function searchAll(q) {
  try {
    const res = await apiClient.get(BASE, { q });
    return { items: Array.isArray(res?.items) ? res.items : [], failed: false };
  } catch (err) {
    console.debug('[search] failed:', err?.message);
    return { items: [], failed: true };
  }
}

export default { searchAll };
