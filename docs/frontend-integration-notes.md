# Frontend ↔ Backend Integration Notes — RBAC Module

All confirmed by inspecting `RbacController.java`, `RbacRequest.java`, `RbacResponse.java`.

---

## Base URL

**`/api/v1/rbac`** — not `/api/rbac`.  
All RBAC endpoints live under `/api/v1/rbac`.

---

## Endpoint mapping corrections (original prompt vs reality)

| Requested endpoint | Actual backend endpoint | Notes |
|---|---|---|
| `GET /api/rbac/roles?orgId=` | `GET /api/v1/rbac/roles?withPermissions=false` | No `orgId` filter on roles — roles are org-scoped at the data layer |
| `POST /api/rbac/roles` | `POST /api/v1/rbac/roles` | Body fields differ — see below |
| `GET /api/rbac/roles/{roleId}/permissions` | `GET /api/v1/rbac/roles/{pidGroup}/permissions` | Path variable is `pidGroup` |
| `POST /api/rbac/roles/{roleId}/permissions` | `PUT /api/v1/rbac/roles/{pidGroup}/permissions` | **PUT not POST** — full matrix replacement |
| `GET /api/rbac/users/{userId}/roles` | **DOES NOT EXIST** | Role is embedded in `UserRbacDto.pidGroup` / `UserRbacDto.groupName` |
| `POST /api/rbac/users/{userId}/roles/{roleId}` | **DOES NOT EXIST** | Use `PUT /api/v1/rbac/users/{pidUser}` with body `{ pidGroup: roleId }` |
| `GET /api/rbac/modules` | `GET /api/v1/rbac/modules?activeOnly=true` | |

---

## Field name corrections

### RoleDto (response from GET /roles)

| Frontend assumed | Actual field | Notes |
|---|---|---|
| `roleCode` | `groupCode` | Same value, different key name in DTO |
| `groupDescription` | `description` | Alias in service layer maps `groupDescription` → `description` |
| `roleColor` | **MISSING from RoleDto** | `roleColor` is stored in `AclGroup` entity but `RoleDto` does not expose it |

**GAP — roleColor missing from RoleDto:**  
`RoleDto` does not include a `roleColor` field even though `AclGroup` has the column.  
The frontend falls back to `DEFAULT_ROLE_COLORS` when `r.roleColor` is undefined.  
**Fix needed in backend:** Add `roleColor` to `RbacResponse.RoleDto` and populate it in `toRoleDto()`.

### UserRbacDto (response from GET /users)

| Frontend assumed | Actual field | Notes |
|---|---|---|
| `pidRole` | `pidGroup` | Role FK is exposed as `pidGroup` in DTO |
| `status` (`"Y"/"N"`) | `isActive` (Integer 0\|1) | Derived from `BaseEntity.status` |
| `lastLogin` | **MISSING** | Not in `UserRbacDto` — `lastLogin` is in `AclUser` entity but not mapped to DTO |

**GAP — lastLogin missing from UserRbacDto:**  
Frontend shows "Never" / creation date as fallback.  
**Fix needed in backend:** Add `lastLogin` to `UserRbacDto` and populate in `toUserDto()`.

---

## Permission flags type mismatch

`PermissionDto` uses **Integer (0|1)** for all permission flags.  
`MenuItemDto` uses **primitive boolean** for the same flags.  
Frontend normalises both to boolean in `getPermission()` via `=== 1 || === true`.

---

## Avatar upload — DOES NOT EXIST

No multipart/file upload endpoint exists anywhere in the backend.  
`avatarUrl` in `CreateUserRequest` and `UpdateUserRequest` is a **plain URL string**.  
To show an avatar, pass a public image URL when creating/updating a user.

**Workaround:** Accept an image URL input in the UI (e.g. paste a URL).  
**Fix needed in backend if file upload is required:**  
Implement `POST /api/v1/users/{pidUser}/avatar` accepting `multipart/form-data`,  
store to disk/S3, and return the URL to save in `avatar_url`.

---

## Missing dedicated user-role assignment endpoint

There is no `GET /users/{id}/roles` or `POST /users/{id}/roles/{roleId}`.  
A user has exactly one role (`pidGroup`).  
Use `PUT /api/v1/rbac/users/{pidUser}` with body `{ "pidGroup": <roleId> }` to change it.

---

## Permissions — PUT not POST

`PUT /api/v1/rbac/roles/{pidGroup}/permissions` replaces the **full matrix** atomically.  
Always send all modules in the payload, including those with all-zero flags.  
Do not use POST for permissions — it does not exist.

### Correct request body
```json
{
  "permissions": [
    { "pidModule": 1, "canView": 1, "canCreate": 1, "canEdit": 1, "canDelete": 0, "canExport": 0, "canApprove": 0 },
    { "pidModule": 2, "canView": 1, "canCreate": 0, "canEdit": 0, "canDelete": 0, "canExport": 0, "canApprove": 0 }
  ]
}
```
All six flags (`canView`, `canCreate`, `canEdit`, `canDelete`, `canExport`, `canApprove`) are `@NotNull` — **always send all six**.

---

## Delete safeguards (backend enforced)

### Delete role
- Blocked if role is `ROLE01` (Super Admin)
- Blocked if any users are assigned to the role → `"Cannot delete role: N user(s) are assigned"`

### Delete module
- Blocked if module has child modules
- Blocked if module has permissions assigned

Frontend should surface the error message from the API response on failure.

---

## Summary of backend fixes needed

| Priority | Gap | Backend change needed |
|---|---|---|
| ~~High~~ | ~~`RoleDto` missing `roleColor`~~ | ✅ Fixed — `roleColor` added to `RoleDto` and `toRoleDto()` |
| Medium | `UserRbacDto` missing `lastLogin` | Add `lastLogin LocalDateTime` to `UserRbacDto`, populate in `toUserDto()` |
| Low | Avatar file upload | New endpoint `POST /api/v1/rbac/users/{pidUser}/avatar` accepting multipart |
