/**
 * [V71] Super Admin password administration.
 *
 * There is no "fetch the password" call here and there cannot be one. Passwords
 * are stored as BCrypt hashes — a one-way function with no key — so the value
 * that was typed is not recoverable from what is stored, by this application or
 * by anything else. `passwordStatus` on each account is what answers the
 * question a support call actually asks: what state is this login in, and what
 * do I do about it.
 *
 * The only way to give someone a working password is `reset`, which generates a
 * new one and returns it exactly once.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/admin/passwords';

/* What each status word means, in the words the screen shows. Kept beside the
   service rather than in the component because the API returns the word and the
   UI must not invent a different vocabulary for it. */
export const PASSWORD_STATUS = {
  ACTIVE:    { label: 'Active',      tone: 'sage',
               hint: 'Set by the user and stored as a one-way hash.' },
  TEMPORARY: { label: 'Temporary',   tone: 'amber',
               hint: 'Holding a password an administrator issued. The user must replace it at next sign-in.' },
  EXPIRED:   { label: 'Expired',     tone: 'clay',
               hint: 'The temporary password has run out and no longer works. Issue a new one.' },
  WEAK:      { label: 'Not hashed',  tone: 'clay',
               hint: 'Stored in the clear from before the migration. Resetting it fixes this immediately.' },
  UNSET:     { label: 'No password', tone: 'slate',
               hint: 'Nothing is stored. This account cannot sign in at all.' },
};

export const statusOf = (word) => PASSWORD_STATUS[word] ?? { label: word || '—', tone: 'slate', hint: '' };

export default {
  /** Every account on the installation, with the state of its credential. */
  accounts: () => apiClient.get(`${BASE}/accounts`).then((r) => r?.data ?? r),

  /**
   * Issue a temporary password for one account.
   *
   * The response carries the password in clear. It is the only time it exists
   * outside a hash, so the caller must show it and must not re-request it —
   * there is nothing to re-request.
   */
  reset: (pidUser) => apiClient.post(`${BASE}/${pidUser}/reset`).then((r) => r?.data ?? r),

  /** The reset trail, newest first. Refusals are included, not only successes. */
  audit: (pidUser = null, limit = 100) =>
    apiClient.get(`${BASE}/audit`, pidUser ? { pidUser, limit } : { limit })
      .then((r) => r?.data ?? r),
};
