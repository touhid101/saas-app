/**
 * RBAC Service — Role-Based Access Control
 * Base path: /api/v1/rbac  (confirmed from RbacController @RequestMapping)
 *
 * All functions strip the ApiResponse wrapper via apiClient (which already
 * extracts `.data` from the backend envelope).
 */
import apiClient from '../lib/apiClient';

const BASE = '/api/v1/rbac';

// ─────────────────────────────────────────────────────────────────────────────
// MENU
// ─────────────────────────────────────────────────────────────────────────────

/** GET /api/v1/rbac/menu — dynamic sidebar for the current user */
export async function getMyMenu() {
  return apiClient.get(`${BASE}/menu`);
}

/**
 * GET /api/v1/rbac/capabilities — what this user can do that has no page.
 *
 * The complement of the menu: Notifications, Global Search and anything else
 * that is a permission without a screen behind it. Loaded once at sign-in so
 * the topbar knows which buttons to draw without probing an endpoint each.
 *
 * Never throws — a topbar with no buttons is better than a page that will not
 * render.
 */
export async function getMyCapabilities() {
  try {
    const codes = await apiClient.get(`${BASE}/capabilities`);
    return Array.isArray(codes) ? codes : [];
  } catch (err) {
    console.debug('[rbac] capabilities unavailable:', err?.message);
    return [];
  }
}
/** GET /api/v1/rbac/menu/user/{pidUser} — admin: menu for any user */
export async function getMenuForUser(pidUser) {
  return apiClient.get(`${BASE}/menu/user/${pidUser}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/rbac/modules?activeOnly=true
 * Returns List<ModuleDto> — flat list
 * ModuleDto fields: pidModule, moduleCode, moduleName, moduleDesc,
 *                   iconKey, routePath, pidParentModule, sortOrder,
 *                   isActive, children (null in flat list)
 */
export async function getAllModules(activeOnly = true) {
  return apiClient.get(`${BASE}/modules`, { activeOnly });
}

/**
 * GET /api/v1/rbac/modules/tree?activeOnly=true
 * Returns List<ModuleDto> with nested children[]
 */
export async function getModuleTree(activeOnly = true) {
  return apiClient.get(`${BASE}/modules/tree`, { activeOnly });
}

/**
 * GET /api/v1/rbac/modules/{pidModule}
 */
export async function getModule(pidModule) {
  return apiClient.get(`${BASE}/modules/${pidModule}`);
}

/**
 * POST /api/v1/rbac/modules  (ROLE_ADMIN)
 * Body: CreateModuleRequest
 *   moduleName   String  @NotBlank
 *   moduleCode   String  optional — auto-generated as MOD01, MOD02… if omitted
 *   moduleDesc   String  optional
 *   iconKey      String  optional
 *   routePath    String  optional
 *   pidParentModule Long optional (null = top-level)
 *   sortOrder    Integer optional
 */
export async function createModule({ moduleName, moduleCode, moduleDesc, iconKey, routePath, pidParentModule, sortOrder } = {}) {
  return apiClient.post(`${BASE}/modules`, {
    moduleName,
    ...(moduleCode   && { moduleCode }),
    ...(moduleDesc   && { moduleDesc }),
    ...(iconKey      && { iconKey }),
    ...(routePath    && { routePath }),
    ...(pidParentModule != null && { pidParentModule }),
    ...(sortOrder    != null    && { sortOrder }),
  });
}

/**
 * PUT /api/v1/rbac/modules/{pidModule}  (ROLE_ADMIN)
 * Body: UpdateModuleRequest — all fields optional
 *   moduleName, moduleDesc, iconKey, routePath, sortOrder, isActive
 */
/**
 * PUT /api/v1/rbac/modules/{pidModule}
 *
 * Every field is optional and absent means "leave it alone" — which is why
 * moving a module to the top level, or turning a page into a menu group, each
 * need an explicit flag. A plain null cannot say whether it means "no parent"
 * or "do not touch the parent", and this used to drop `pidParentModule`
 * entirely, so a module could never be re-parented once created.
 */
export async function updateModule(pidModule, {
  moduleName, moduleDesc, iconKey, routePath, sortOrder, isActive, pidParentModule,
} = {}) {
  return apiClient.put(`${BASE}/modules/${pidModule}`, {
    ...(moduleName != null && { moduleName }),
    ...(moduleDesc != null && { moduleDesc }),
    ...(iconKey    != null && { iconKey }),
    ...(sortOrder  != null && { sortOrder }),
    ...(isActive   != null && { isActive }),
    // null asks for the route to be cleared, which is how a page becomes a group.
    ...(routePath === null ? { clearRoutePath: true }
                           : routePath != null && { routePath }),
    ...(pidParentModule === null ? { clearParent: true }
                                 : pidParentModule != null && { pidParentModule }),
  });
}

/**
 * DELETE /api/v1/rbac/modules/{pidModule}  (ROLE_ADMIN)
 * Backend blocks if module has child modules or permissions assigned.
 */
export async function deleteModule(pidModule) {
  return apiClient.delete(`${BASE}/modules/${pidModule}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// ROLES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/rbac/roles?withPermissions=false
 * Returns List<RoleDto>
 * RoleDto fields: pidGroup, groupName, groupCode (= roleCode), description,
 *                 isActive, createdAt, permissions[] (only when withPermissions=true)
 *
 * NOTE: backend also exposes roleColor via AclGroup but RoleDto does not
 * currently include it — see docs/frontend-integration-notes.md
 */
export async function getAllRoles(withPermissions = false) {
  return apiClient.get(`${BASE}/roles`, { withPermissions });
}

/**
 * GET /api/v1/rbac/roles/{pidGroup}
 * Returns RoleDto with permissions[] populated
 */
export async function getRole(pidGroup) {
  return apiClient.get(`${BASE}/roles/${pidGroup}`);
}

/**
 * POST /api/v1/rbac/roles  (ROLE_ADMIN)
 * Body: CreateRoleRequest
 *   groupName   String  @NotBlank
 *   groupCode   String  optional — auto-generated as ROLE10, ROLE11… if omitted
 *   description String  optional  (maps to GROUP_DESCRIPTION)
 *   roleColor   String  optional  hex string e.g. "#2E6A62"
 */
export async function createRole({ groupName, groupCode, description, roleColor } = {}) {
  return apiClient.post(`${BASE}/roles`, {
    groupName,
    ...(groupCode   && { groupCode }),
    ...(description && { description }),
    ...(roleColor   && { roleColor }),
  });
}

/**
 * PUT /api/v1/rbac/roles/{pidGroup}  (ROLE_ADMIN)
 * Body: UpdateRoleRequest — all fields optional
 *   groupName, description, roleColor, isActive
 */
export async function updateRole(pidGroup, { groupName, description, roleColor, isActive } = {}) {
  return apiClient.put(`${BASE}/roles/${pidGroup}`, {
    ...(groupName   != null && { groupName }),
    ...(description != null && { description }),
    ...(roleColor   != null && { roleColor }),
    ...(isActive    != null && { isActive }),
  });
}

/**
 * DELETE /api/v1/rbac/roles/{pidGroup}  (ROLE_ADMIN)
 * Backend blocks if:
 *   - role is ROLE01 / Super Admin
 *   - any users are assigned to this role
 */
export async function deleteRole(pidGroup) {
  return apiClient.delete(`${BASE}/roles/${pidGroup}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSIONS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/rbac/roles/{pidGroup}/permissions
 * Returns List<PermissionDto>
 * PermissionDto fields: pidPermission, pidRole, roleName, pidModule,
 *   moduleName, moduleCode, iconKey,
 *   canView, canCreate, canEdit, canDelete, canExport, canApprove  (all Integer 0|1)
 */
export async function getRolePermissions(pidGroup) {
  return apiClient.get(`${BASE}/roles/${pidGroup}/permissions`);
}

/**
 * PUT /api/v1/rbac/roles/{pidGroup}/permissions  (ROLE_ADMIN)
 * Atomically replaces ALL permissions for the role.
 *
 * @param {number} pidGroup  — role ID
 * @param {Array}  entries   — array of permission objects:
 *   [{ pidModule, canView, canCreate, canEdit, canDelete, canExport, canApprove }]
 *   All integer values must be 0 or 1.  All six flags are @NotNull — always send all six.
 *
 * Body shape sent to backend:
 *   { permissions: [ { pidModule, canView, canCreate, canEdit, canDelete, canExport, canApprove } ] }
 */
export async function setRolePermissions(pidGroup, entries) {
  // Normalise: accept both boolean and 0/1; ensure all six flags present
  const permissions = entries.map(e => ({
    pidModule:  e.pidModule,
    canView:    e.canView    ? 1 : 0,
    canCreate:  e.canCreate  ? 1 : 0,
    canEdit:    e.canEdit    ? 1 : 0,
    canDelete:  e.canDelete  ? 1 : 0,
    canExport:  e.canExport  ? 1 : 0,
    canApprove: e.canApprove ? 1 : 0,
  }));
  return apiClient.put(`${BASE}/roles/${pidGroup}/permissions`, { permissions });
}

// ─────────────────────────────────────────────────────────────────────────────
// USERS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/rbac/users?orgId={orgId}
 * Returns List<UserRbacDto>
 * UserRbacDto fields: pidUser, username, fullName, email, avatarUrl,
 *   pidGroup, groupName, isActive (Integer 0|1), createdAt,
 *   permissions[] (effective permissions from their role)
 *
 * NOTE: no lastLogin or status fields in UserRbacDto — see integration notes.
 */
export async function getAllUsers(orgId) {
  return apiClient.get(`${BASE}/users`, { orgId });
}

/**
 * GET /api/v1/rbac/users/{pidUser}
 */
export async function getUser(pidUser) {
  return apiClient.get(`${BASE}/users/${pidUser}`);
}

/**
 * POST /api/v1/rbac/users  (ROLE_ADMIN)
 * Body: CreateUserRequest
 *   username    String  @NotBlank
 *   password    String  @NotBlank
 *   fullName    String  @NotBlank
 *   email       String  optional
 *   phoneNo     String  optional  (stored in mobile_no column)
 *   pidGroup    Long    @NotNull  — role FK  (NOT pidRole — that's the entity name)
 *   pidOrg      Long    @NotNull
 *   pidBranch   Long    optional
 *   avatarUrl   String  optional  (URL string only — no file upload endpoint exists)
 *   pidEmployee Long    required for ROLE06 (Delivery Staff), optional otherwise —
 *                       the staff record this login belongs to. An invoice records
 *                       its driver as an employee id, so without it a signed-in
 *                       driver cannot be matched to their own deliveries.
 */
/**
 * `pidBranch` decides everything this login will ever see.
 *
 * Sent unconditionally rather than only when set: the server requires it, and
 * a user created without one can sign in and then read nothing, which looks
 * like a broken application rather than a half-filled form.
 */
export async function createUser({ username, password, fullName, email, phoneNo, pidGroup, pidOrg, pidBranch, avatarUrl, pidEmployee } = {}) {
  return apiClient.post(`${BASE}/users`, {
    username,
    password,
    fullName,
    pidGroup,
    pidOrg,
    ...(email     && { email }),
    ...(phoneNo   && { phoneNo }),
    pidBranch,
    ...(avatarUrl && { avatarUrl }),
    ...(pidEmployee != null && { pidEmployee }),
  });
}

/**
 * PUT /api/v1/rbac/users/{pidUser}  (ROLE_ADMIN)
 * Body: UpdateUserRequest — all fields optional
 *   fullName, email, phoneNo, pidGroup, isActive, avatarUrl
 */
export async function updateUser(pidUser, { fullName, email, phoneNo, pidGroup, pidBranch, isActive, avatarUrl, pidEmployee } = {}) {
  return apiClient.put(`${BASE}/users/${pidUser}`, {
    ...(fullName  != null && { fullName }),
    ...(email     != null && { email }),
    ...(phoneNo   != null && { phoneNo }),
    ...(pidGroup  != null && { pidGroup }),
    ...(pidBranch != null && { pidBranch }),
    ...(isActive  != null && { isActive }),
    ...(avatarUrl != null && { avatarUrl }),
    ...(pidEmployee !== undefined && { pidEmployee }),
  });
}

/**
 * PATCH /api/v1/rbac/users/{pidUser}/password
 * Body: { oldPassword, newPassword }
 *
 * The backend requires oldPassword when a user changes their OWN password and
 * verifies it against the stored credential. Admins (ROLE01/ROLE02) may reset
 * another user's password without it. The new password must differ from the
 * current one.
 *
 * For self-service prefer authService.changeMyPassword() — it resolves the user
 * id from the token instead of trusting one supplied by the client.
 */
export async function changePassword(pidUser, oldPassword, newPassword) {
  return apiClient.patch(`${BASE}/users/${pidUser}/password`, {
    ...(oldPassword ? { oldPassword } : {}),
    newPassword,
  });
}

/**
 * PATCH /api/v1/rbac/users/{pidUser}/activate  (ROLE_ADMIN)
 */
export async function activateUser(pidUser) {
  return apiClient.patch(`${BASE}/users/${pidUser}/activate`);
}

/**
 * PATCH /api/v1/rbac/users/{pidUser}/deactivate  (ROLE_ADMIN)
 */
export async function deactivateUser(pidUser) {
  return apiClient.patch(`${BASE}/users/${pidUser}/deactivate`);
}

/**
 * POST /api/v1/rbac/users/{pidUser}/avatar  (ROLE_ADMIN)
 * Uploads a jpg/jpeg/png image, saves to server local disk under uploads/avatars/,
 * persists the public URL in avatar_url column and returns the updated UserRbacDto.
 * @param {number} pidUser
 * @param {File}   file   — File object from <input type="file">
 */
export async function uploadUserAvatar(pidUser, file) {
  const formData = new FormData();
  formData.append('file', file);
  return apiClient.upload(`${BASE}/users/${pidUser}/avatar`, formData);
}

// ─────────────────────────────────────────────────────────────────────────────
// GAPS — endpoints the UI needs but backend does NOT expose
// See docs/frontend-integration-notes.md for full details.
// ─────────────────────────────────────────────────────────────────────────────
//
// ✗  GET  /api/rbac/roles?orgId=  — backend uses /api/v1/rbac/roles (no orgId param)
// ✗  GET  /api/rbac/users/{userId}/roles  — no dedicated endpoint; role comes in UserRbacDto.pidGroup
// ✗  POST /api/rbac/users/{userId}/roles/{roleId} — use updateUser(pid,{pidGroup}) instead
// ─────────────────────────────────────────────────────────────────────────────

export default {
  // Menu
  getMyMenu,
  getMenuForUser,
  // Modules
  getAllModules,
  getModuleTree,
  getModule,
  createModule,
  updateModule,
  deleteModule,
  // Roles
  getAllRoles,
  getRole,
  createRole,
  updateRole,
  deleteRole,
  // Permissions
  getRolePermissions,
  setRolePermissions,
  // Users
  getAllUsers,
  getUser,
  createUser,
  updateUser,
  changePassword,
  activateUser,
  deactivateUser,
  uploadUserAvatar,
};
