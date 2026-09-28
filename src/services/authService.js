/**
 * Authentication Service
 * Handles login, logout, token refresh, and registration
 */
import apiClient from '../lib/apiClient.js';

/**
 * Login with username and password
 * @param {string} username
 * @param {string} password
 * @returns {Promise<{accessToken, refreshToken, tokenType, expiresIn, user}>}
 */
export async function login(username, password) {
  const response = await apiClient.post('/api/auth/login', {
    username,
    password
  });

  // Response shape: { accessToken, refreshToken, tokenType, expiresIn, user }
  const { accessToken, refreshToken, user } = response;

  // Store auth state
  apiClient.setAuth(accessToken, refreshToken, user);

  return response;
}

/**
 * Logout current user
 */
export async function logout() {
  try {
    await apiClient.post('/api/auth/logout');
  } catch (error) {
    // Log but don't throw - we still want to clear local auth state
    console.warn('[AuthService] Logout request failed:', error.message);
  } finally {
    // Always clear local auth state
    apiClient.clearAuth();
  }
}

/**
 * Refresh access token using refresh token
 * @returns {Promise<{accessToken, refreshToken}>}
 */
export async function refreshToken() {
  const { refreshToken } = apiClient.getAuth();

  if (!refreshToken) {
    throw new Error('No refresh token available');
  }

  const response = await apiClient.post('/api/auth/refresh', {
    refreshToken
  });

  // Response shape: { accessToken, refreshToken }
  const { accessToken, refreshToken: newRefreshToken } = response;

  // Update tokens
  const { user } = apiClient.getAuth();
  apiClient.setAuth(accessToken, newRefreshToken || refreshToken, user);

  return response;
}

/**
 * Register new user
 * @param {object} userData - { username, password, email, fullName, pidOrg, pidBranch }
 * @returns {Promise<{accessToken, refreshToken, user}>}
 */
export async function register(userData) {
  const response = await apiClient.post('/api/auth/register', userData);

  // If registration returns auth tokens, store them
  if (response.accessToken && response.refreshToken) {
    const { accessToken, refreshToken, user } = response;
    apiClient.setAuth(accessToken, refreshToken, user);
  }

  return response;
}

/**
 * GET /api/auth/me — fetch the authenticated user fresh from the server
 * and refresh the locally cached copy.
 * @returns {Promise<object>} UserResponse
 */
export async function getMe() {
  const user = await apiClient.get('/api/auth/me');
  if (user) apiClient.setStoredUser(user);
  return user;
}

/**
 * PUT /api/auth/me — update the current user's own profile.
 * Role, branch and active flag are intentionally not editable here.
 * @param {{fullName: string, email?: string, mobileNo?: string}} profile
 * @returns {Promise<object>} updated UserResponse
 */
export async function updateMyProfile({ fullName, email, mobileNo }) {
  const user = await apiClient.put('/api/auth/me', {
    fullName,
    email: email ?? '',
    mobileNo: mobileNo ?? '',
  });
  if (user) apiClient.setStoredUser(user);
  return user;
}

/**
 * PATCH /api/auth/me/password — change own password.
 * The server verifies oldPassword against the stored credential and rejects a
 * newPassword identical to it, so no client-side trust is involved.
 * @param {string} oldPassword current password
 * @param {string} newPassword new password (min 6 chars)
 */
export async function changeMyPassword(oldPassword, newPassword) {
  return apiClient.patch('/api/auth/me/password', { oldPassword, newPassword });
}

/**
 * POST /api/auth/me/avatar — upload a new avatar for the current user.
 * @param {File} file jpg/jpeg/png, max 2 MB
 * @returns {Promise<object>} updated UserResponse
 */
export async function uploadMyAvatar(file) {
  const formData = new FormData();
  formData.append('file', file);
  const user = await apiClient.upload('/api/auth/me/avatar', formData);
  if (user) apiClient.setStoredUser(user);
  return user;
}

/**
 * Get current authentication state
 * @returns {{accessToken, refreshToken, user, isAuthenticated}}
 */
export function getAuthState() {
  return apiClient.getAuth();
}

/**
 * Check if user is authenticated
 * @returns {boolean}
 */
export function isAuthenticated() {
  return apiClient.getAuth().isAuthenticated;
}

/**
 * Get current user
 * @returns {object|null}
 */
export function getCurrentUser() {
  return apiClient.getAuth().user;
}

/**
 * Check if current user has a specific role
 * @param {string} role - Role name (e.g., "ROLE_ADMIN")
 * @returns {boolean}
 */
export function hasRole(role) {
  const user = getCurrentUser();
  return user && user.roles && user.roles.includes(role);
}

/**
 * Check if current user has any of the specified roles
 * @param {string[]} roles - Array of role names
 * @returns {boolean}
 */
export function hasAnyRole(roles) {
  const user = getCurrentUser();
  if (!user || !user.roles) return false;
  return roles.some(role => user.roles.includes(role));
}

/**
 * Check if current user has all of the specified roles
 * @param {string[]} roles - Array of role names
 * @returns {boolean}
 */
export function hasAllRoles(roles) {
  const user = getCurrentUser();
  if (!user || !user.roles) return false;
  return roles.every(role => user.roles.includes(role));
}

export default {
  login,
  logout,
  refreshToken,
  register,
  getMe,
  updateMyProfile,
  changeMyPassword,
  uploadMyAvatar,
  getAuthState,
  isAuthenticated,
  getCurrentUser,
  hasRole,
  hasAnyRole,
  hasAllRoles
};
