/**
 * API Client for Friends Pharma POS
 * Centralized HTTP client with authentication, error handling, and token refresh.
 *
 * Token expiry strategy:
 *  1. Before every request: decode JWT and check expiry client-side.
 *     If expired and a refresh token exists → proactively refresh before sending.
 *     If expired and no refresh token → fire 'pharma:session-expired' event immediately.
 *  2. On 401 response: attempt refresh once, then retry.
 *  3. On 403 response: check if it's an expired-token 403 (no user in SecurityContext)
 *     vs a real access-denied 403. Expired-token 403 triggers the same refresh flow.
 *  4. If refresh fails for any reason → fire 'pharma:session-expired' event.
 *     AppContext listens for this event and calls logout() gracefully.
 */

/*
 * Where the API lives.
 *
 * An EMPTY value means "the same origin that served this page", which is what
 * a reverse-proxy deployment wants: the requests become relative, so they
 * inherit the scheme, host AND port of the address the user actually typed.
 * Naming the origin explicitly means hosting the site on any other port makes
 * every call cross-origin and the browser blocks it on CORS.
 *
 * ?? rather than ||, because an empty string is falsy: with || it would fall
 * back to localhost and the same-origin setup could never be expressed.
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/* ── Custom error ─────────────────────────────────────────────────────────── */
export class ApiError extends Error {
  constructor(message, errorCode = null, data = null, status = null) {
    super(message);
    this.name = 'ApiError';
    this.errorCode = errorCode;
    this.data = data;
    this.status = status;
  }
}

/* ── Token storage ────────────────────────────────────────────────────────── */
const TokenManager = {
  getAccessToken()          { return localStorage.getItem('pharma_access_token'); },
  setAccessToken(t)         { localStorage.setItem('pharma_access_token', t); },
  getRefreshToken()         { return localStorage.getItem('pharma_refresh_token'); },
  setRefreshToken(t)        { localStorage.setItem('pharma_refresh_token', t); },
  clearTokens() {
    localStorage.removeItem('pharma_access_token');
    localStorage.removeItem('pharma_refresh_token');
    localStorage.removeItem('pharma_user');
  },
  getUser() {
    try { return JSON.parse(localStorage.getItem('pharma_user')); } catch { return null; }
  },
  setUser(u) { localStorage.setItem('pharma_user', JSON.stringify(u)); },
};

/* ── JWT decode (no-verify, client-side expiry check only) ────────────────── */
function decodeJwtPayload(token) {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}

/**
 * Returns true if token is missing or expires within the next 60 seconds.
 */
function isTokenExpiredOrExpiringSoon(token) {
  if (!token) return true;
  const payload = decodeJwtPayload(token);
  if (!payload || !payload.exp) return true;
  const nowSeconds = Math.floor(Date.now() / 1000);
  return payload.exp < nowSeconds + 60; // 60-second buffer
}

/* ── Session-expired event (triggers AppContext logout) ───────────────────── */
let _sessionExpiredFired = false; // prevent duplicate firings in the same request burst

function fireSessionExpired() {
  if (_sessionExpiredFired) return;
  _sessionExpiredFired = true;
  TokenManager.clearTokens();
  window.dispatchEvent(new CustomEvent('pharma:session-expired'));
  // Reset flag after a short delay so future sessions work
  setTimeout(() => { _sessionExpiredFired = false; }, 3000);
}

/* ── Subscription-expired event (shows the renewal screen) ────────────────── */
/**
 * The server answers 402 when a pharmacy has stopped paying.
 *
 * <p>Broadcast rather than handled here, for the same reason the session-expired
 * event is: every screen makes its own requests, and thirty screens each deciding
 * what to do about a 402 is thirty chances to get it wrong. One event, one
 * listener, one renewal screen.
 *
 * The tokens are deliberately NOT cleared — the user is signed in perfectly well,
 * their pharmacy simply owes money, and logging them out would take away the one
 * screen that can fix it.
 */
let _subscriptionExpiredFired = false;

function fireSubscriptionExpired(detail) {
  if (_subscriptionExpiredFired) return;
  _subscriptionExpiredFired = true;
  window.dispatchEvent(new CustomEvent('pharma:subscription-expired', { detail }));
  setTimeout(() => { _subscriptionExpiredFired = false; }, 3000);
}

/* ── Token refresh ────────────────────────────────────────────────────────── */
let _refreshPromise = null; // deduplicate concurrent refresh calls

async function refreshAccessToken() {
  if (_refreshPromise) return _refreshPromise; // reuse in-flight refresh

  const refreshToken = TokenManager.getRefreshToken();
  if (!refreshToken) {
    fireSessionExpired();
    throw new ApiError('Session expired. Please log in again.', 'SESSION_EXPIRED');
  }

  _refreshPromise = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/api/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });

      const json = await response.json();

      if (!response.ok || !json.success) {
        throw new ApiError(json.message || 'Token refresh failed', 'AUTH_REFRESH_FAILED');
      }

      const { accessToken, refreshToken: newRefreshToken } = json.data;
      TokenManager.setAccessToken(accessToken);
      if (newRefreshToken) TokenManager.setRefreshToken(newRefreshToken);
      return accessToken;
    } catch (err) {
      fireSessionExpired();
      throw err;
    } finally {
      _refreshPromise = null;
    }
  })();

  return _refreshPromise;
}

/* ── Headers ──────────────────────────────────────────────────────────────── */
/**
 * Endpoints that carry no token.
 *
 * Mirrors the permitAll list in SecurityConfig. When it drifts out of step the
 * cost is not a pointless Authorization header: the proactive refresh above runs
 * first, finds no refresh token to work with, and reports an expired session for
 * a request that never needed one.
 *
 * That is what "Open a pharmacy account" did from the login screen. Sign-up
 * posts to /api/public/register, which was missing here, so the request was
 * never sent and a visitor with no account was told their session had expired.
 * The '/api/auth/register' this used to list has never existed on the server.
 *
 * '/api/public/' is a prefix because everything below it is permitAll.
 */
const PUBLIC_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/public/'];

function requiresAuth(url) {
  return !PUBLIC_PATHS.some(p => url.includes(p));
}

function buildHeaders(customHeaders = {}) {
  const headers = { 'Content-Type': 'application/json', ...customHeaders };
  const token = TokenManager.getAccessToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

/**
 * Build headers for multipart/FormData uploads.
 * Must NOT set Content-Type — the browser sets it automatically
 * with the correct multipart boundary.
 */
function buildUploadHeaders(customHeaders = {}) {
  const headers = { ...customHeaders }; // no Content-Type
  const token = TokenManager.getAccessToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

/* ── Response handler ─────────────────────────────────────────────────────── */
async function handleResponse(response, url) {
  const ct = response.headers.get('content-type') || '';

  if (!ct.includes('application/json')) {
    if (!response.ok) throw new ApiError(`HTTP ${response.status}`, null, null, response.status);
    return response;
  }

  let json;
  try { json = await response.json(); }
  catch { throw new ApiError('Invalid JSON response from server', 'PARSE_ERROR', null, response.status); }

  if (response.ok && json.success !== false) {
    return json.data !== undefined ? json.data : json;
  }

  throw new ApiError(
    json.message || `HTTP ${response.status}: ${response.statusText}`,
    json.errorCode  || `HTTP_${response.status}`,
    json.data || null,
    response.status
  );
}

/* ── Core fetch (with proactive expiry + 401/403 retry) ───────────────────── */
async function apiFetch(url, options = {}, retryCount = 0) {
  const fullUrl = url.startsWith('http') ? url : `${BASE_URL}${url}`;

  // ── Step 1: proactive token refresh before sending ──
  if (retryCount === 0 && requiresAuth(url)) {
    const token = TokenManager.getAccessToken();
    if (isTokenExpiredOrExpiringSoon(token)) {
      try {
        await refreshAccessToken();
      } catch {
        // fireSessionExpired() already called inside refreshAccessToken
        throw new ApiError('Session expired. Please log in again.', 'SESSION_EXPIRED');
      }
    }
  }

  const headers = buildHeaders(options.headers);

  try {
    const response = await fetch(fullUrl, { ...options, headers });

    // ── Step 2: handle 401 / 403 from server (token rejected by backend) ──
    if ((response.status === 401 || response.status === 403) && requiresAuth(url) && retryCount === 0) {
      // Two very different things arrive with these codes, and telling them
      // apart is what stops the app throwing a signed-in user back to the
      // login screen for a refusal that has nothing to do with their session.
      //
      //   · The token was rejected. Spring Security answers before any handler
      //     runs, so the body is empty — no envelope, no errorCode.
      //   · The server understood, and said no: supervisor credentials that
      //     did not match, an action this role may not take. Those come back
      //     through the exception handler with our envelope and an errorCode,
      //     which is proof the session is alive and was read successfully.
      //
      // Previously only errorCode 'ACCESS_DENIED' was spared, so every other
      // business refusal — a mistyped administrator password on the invoice
      // unlock, say — was read as an expired session, silently retried, and
      // then logged the operator out mid-task.
      let serverSaidNo = false;
      try {
        const body = await response.clone().json();
        serverSaidNo = !!body?.errorCode;
      } catch { /* no parseable body — the token really was rejected */ }

      if (!serverSaidNo) {
        try {
          await refreshAccessToken();
          return apiFetch(url, options, retryCount + 1); // retry with new token
        } catch {
          throw new ApiError('Session expired. Please log in again.', 'SESSION_EXPIRED');
        }
      }
    }

    return await handleResponse(response, url);
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === 'SESSION_EXPIRED') {
      fireSessionExpired();
    }
    if (error instanceof ApiError && error.status === 402) {
      fireSubscriptionExpired({ message: error.message, data: error.data });
    }
    console.error('[ApiClient] Request failed:', {
      url: fullUrl, method: options.method || 'GET',
      error: error.message, errorCode: error.errorCode,
    });
    throw error;
  }
}

/* ── Public API ───────────────────────────────────────────────────────────── */
const apiClient = {
  async get(url, params = null, headers = undefined) {
    let fullUrl = url;
    if (params) {
      const qs = new URLSearchParams(
        Object.entries(params).filter(([, v]) => v != null)
      ).toString();
      fullUrl = `${url}?${qs}`;
    }
    return apiFetch(fullUrl, { method: 'GET', headers });
  },

  async post(url, data = null, headers = undefined) {
    return apiFetch(url, { method: 'POST', body: data ? JSON.stringify(data) : undefined, headers });
  },

  // Optional per-request headers, for things that authorise a request
  // rather than form part of it — an unlock token, say. Keeping it out of
  // the body means the server never has to validate and then discard it.
  async put(url, data = null, headers = undefined) {
    return apiFetch(url, { method: 'PUT', body: data ? JSON.stringify(data) : undefined, headers });
  },

  async delete(url, headers = undefined) {
    return apiFetch(url, { method: 'DELETE', headers });
  },

  async patch(url, data = null, headers = undefined) {
    return apiFetch(url, { method: 'PATCH', body: data ? JSON.stringify(data) : undefined, headers });
  },

  async upload(url, formData) {
    // For multipart uploads we MUST NOT set Content-Type — the browser sets it
    // automatically with the correct multipart/form-data boundary.
    const fullUrl = url.startsWith('http') ? url : `${BASE_URL}${url}`;
    const headers = buildUploadHeaders();

    try {
      const response = await fetch(fullUrl, { method: 'POST', headers, body: formData });
      return await handleResponse(response, url);
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'SESSION_EXPIRED') {
        fireSessionExpired();
      }
      console.error('[ApiClient] Upload failed:', { url: fullUrl, error: error.message });
      throw error;
    }
  },

  async download(url, params = null) {
    let fullUrl = url;
    if (params) {
      const qs = new URLSearchParams(
        Object.entries(params).filter(([, v]) => v != null)
      ).toString();
      fullUrl = `${url}?${qs}`;
    }
    return apiFetch(fullUrl, { method: 'GET' });
  },

  getAuth() {
    return {
      accessToken:     TokenManager.getAccessToken(),
      refreshToken:    TokenManager.getRefreshToken(),
      user:            TokenManager.getUser(),
      isAuthenticated: !!TokenManager.getAccessToken(),
    };
  },

  setAuth(accessToken, refreshToken, user) {
    TokenManager.setAccessToken(accessToken);
    TokenManager.setRefreshToken(refreshToken);
    TokenManager.setUser(user);
  },

  /** Replace the cached user object without touching the tokens. */
  setStoredUser(user) { TokenManager.setUser(user); },

  clearAuth() { TokenManager.clearTokens(); },

  unwrapPagination(response) {
    if (!response || typeof response !== 'object') {
      return { items: [], total: 0, page: 0, pageSize: 0, totalPages: 0, hasNext: false, hasPrevious: false };
    }
    return {
      items:       response.content      || [],
      total:       response.totalElements || 0,
      page:        response.currentPage  || 0,
      pageSize:    response.pageSize     || 20,
      totalPages:  response.totalPages   || 0,
      hasNext:     response.hasNext      || false,
      hasPrevious: response.hasPrevious  || false,
    };
  },
};

export default apiClient;
export { TokenManager };
