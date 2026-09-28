package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.response.RbacResponse;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * RBAC endpoints.
 *
 * Authorization strategy — uses role-code style consistent with MasterDataController:
 *   @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
 *
 * Spring Security prepends "ROLE_" automatically, so 'ROLE01' → checks for authority ROLE_ROLE01.
 * UserPrincipal grants:
 *   - Super Admin (ROLE01):  ROLE_ADMIN, ROLE_SUPER_ADMIN, ROLE_ROLE01..ROLE_ROLE09
 *   - Other users:           ROLE_<their role code>  (e.g. ROLE_ROLE02)
 */
@RestController
@RequestMapping("/api/v1/rbac")
@RequiredArgsConstructor
@Tag(name = "RBAC", description = "Role-Based Access Control — modules, roles, permissions, users")
public class RbacController {

    private final PermissionService permissionService;

    // ══════════════════════════════════════════════════════════════════════
    // MODULES
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Create a new module / menu item")
    public ApiResponse<RbacResponse.ModuleDto> createModule(
            @Valid @RequestBody RbacRequest.CreateModuleRequest req) {
        return ApiResponse.success(permissionService.createModule(req), "Module created");
    }

    @PutMapping("/modules/{pidModule}")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Update a module")
    public ApiResponse<RbacResponse.ModuleDto> updateModule(
            @PathVariable Long pidModule,
            @Valid @RequestBody RbacRequest.UpdateModuleRequest req) {
        return ApiResponse.success(permissionService.updateModule(pidModule, req));
    }

    @DeleteMapping("/modules/{pidModule}")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Delete a module (also removes all permissions referencing it)")
    public ApiResponse<Void> deleteModule(@PathVariable Long pidModule) {
        permissionService.deleteModule(pidModule);
        return ApiResponse.success(null, "Module deleted");
    }

    @GetMapping("/modules")
    @Operation(summary = "List all modules (flat list)")
    public ApiResponse<List<RbacResponse.ModuleDto>> listModules(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(permissionService.getAllModules(activeOnly));
    }

    @GetMapping("/modules/tree")
    @Operation(summary = "Module tree (parents with nested children) — used by frontend menu builder")
    public ApiResponse<List<RbacResponse.ModuleDto>> moduleTree(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(permissionService.getModuleTree(activeOnly));
    }

    @GetMapping("/modules/{pidModule}")
    @Operation(summary = "Get a single module")
    public ApiResponse<RbacResponse.ModuleDto> getModule(@PathVariable Long pidModule) {
        return ApiResponse.success(permissionService.getModuleById(pidModule));
    }

    /**
     * Who can reach this module — the same data read down the other axis.
     *
     * <p>The Permissions tab answers "what can this role do?". Answering "who can
     * reach Purchase Invoice?" there means opening nine roles in turn and
     * remembering what each one said.
     */
    @GetMapping("/modules/{pidModule}/roles")
    @Operation(summary = "Every role's rights on one module")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> moduleRoles(
            @PathVariable Long pidModule) {
        return ApiResponse.success(permissionService.getModuleRoles(pidModule));
    }

    @PutMapping("/modules/{pidModule}/roles")
    /* It carried no authorisation at all, so any signed-in account could rewrite
       which roles hold a module — for every pharmacy on the estate. Roles and
       modules are global, so this is the platform's to change. */
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Grant or remove this module across roles",
               description = "Touches only this module. A role's other modules are left alone — "
                           + "unlike setting a role's permissions, which replaces all of them.")
    @SuppressWarnings("unchecked")
    public ApiResponse<Integer> setModuleRoles(
            @PathVariable Long pidModule,
            @RequestBody java.util.Map<String, Object> body) {
        Object raw = body.get("roles");
        java.util.List<java.util.Map<String, Object>> rows =
                raw instanceof java.util.List<?> l
                        ? (java.util.List<java.util.Map<String, Object>>) l
                        : java.util.List.of();
        int granted = permissionService.setModuleRoles(pidModule, rows);
        return ApiResponse.success(granted,
                granted == 0 ? "No role holds this module now."
                             : granted + " role(s) hold this module.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ROLES
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Create a new role")
    public ApiResponse<RbacResponse.RoleDto> createRole(
            @Valid @RequestBody RbacRequest.CreateRoleRequest req) {
        return ApiResponse.success(permissionService.createRole(req), "Role created");
    }

    @PutMapping("/roles/{pidGroup}")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Update a role")
    public ApiResponse<RbacResponse.RoleDto> updateRole(
            @PathVariable Long pidGroup,
            @Valid @RequestBody RbacRequest.UpdateRoleRequest req) {
        return ApiResponse.success(permissionService.updateRole(pidGroup, req));
    }

    @DeleteMapping("/roles/{pidGroup}")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Delete a role (also removes all permissions for that role)")
    public ApiResponse<Void> deleteRole(@PathVariable Long pidGroup) {
        permissionService.deleteRole(pidGroup);
        return ApiResponse.success(null, "Role deleted");
    }

    @GetMapping("/roles")
    @Operation(summary = "List all roles")
    public ApiResponse<List<RbacResponse.RoleDto>> listRoles(
            @RequestParam(defaultValue = "false") boolean withPermissions) {
        return ApiResponse.success(permissionService.getAllRoles(withPermissions));
    }

    @GetMapping("/roles/{pidGroup}")
    @Operation(summary = "Get role with permissions")
    public ApiResponse<RbacResponse.RoleDto> getRole(@PathVariable Long pidGroup) {
        return ApiResponse.success(permissionService.getRoleById(pidGroup));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════════════════════════════════

    @PutMapping("/roles/{pidGroup}/permissions")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Replace all permissions for a role (full matrix update)")
    public ApiResponse<List<RbacResponse.PermissionDto>> setPermissions(
            @PathVariable Long pidGroup,
            @Valid @RequestBody RbacRequest.SetRolePermissionsRequest req) {
        return ApiResponse.success(
                permissionService.setRolePermissions(pidGroup, req),
                "Permissions updated");
    }

    @GetMapping("/roles/{pidGroup}/permissions")
    @Operation(summary = "Get all permissions for a role")
    public ApiResponse<List<RbacResponse.PermissionDto>> getPermissions(
            @PathVariable Long pidGroup) {
        return ApiResponse.success(permissionService.getRolePermissions(pidGroup));
    }

    // ══════════════════════════════════════════════════════════════════════
    // USERS
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Create a new user and assign to a role")
    public ApiResponse<RbacResponse.UserRbacDto> createUser(
            @Valid @RequestBody RbacRequest.CreateUserRequest req) {
        return ApiResponse.success(permissionService.createUser(req), "User created");
    }

    @PutMapping("/users/{pidUser}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Update user details (name, email, role, avatar, active flag)")
    public ApiResponse<RbacResponse.UserRbacDto> updateUser(
            @PathVariable Long pidUser,
            @Valid @RequestBody RbacRequest.UpdateUserRequest req) {
        return ApiResponse.success(permissionService.updateUser(pidUser, req));
    }

    @PostMapping(path = "/users/{pidUser}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Upload avatar image for a user — stored on local disk, returns public URL")
    public ApiResponse<RbacResponse.UserRbacDto> uploadAvatar(
            @PathVariable Long pidUser,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(permissionService.uploadUserAvatar(pidUser, file), "Avatar uploaded");
    }

    @PatchMapping("/users/{pidUser}/password")
    @Operation(summary = "Change user password (admin or the user themselves)")
    public ApiResponse<Void> changePassword(
            @PathVariable Long pidUser,
            @Valid @RequestBody RbacRequest.ChangePasswordRequest req) {
        permissionService.changePassword(pidUser, req);
        return ApiResponse.success(null, "Password changed");
    }

    @PatchMapping("/users/{pidUser}/activate")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Activate a user account")
    public ApiResponse<Void> activate(@PathVariable Long pidUser) {
        permissionService.toggleUserActive(pidUser, true);
        return ApiResponse.success(null, "User activated");
    }

    @PatchMapping("/users/{pidUser}/deactivate")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Deactivate a user account")
    public ApiResponse<Void> deactivate(@PathVariable Long pidUser) {
        permissionService.toggleUserActive(pidUser, false);
        return ApiResponse.success(null, "User deactivated");
    }

    @GetMapping("/users/{pidUser}")
    @Operation(summary = "Get user with effective permissions")
    public ApiResponse<RbacResponse.UserRbacDto> getUser(@PathVariable Long pidUser) {
        return ApiResponse.success(permissionService.getUserById(pidUser));
    }

    @GetMapping("/users")
    @Operation(summary = "List all users for an organisation")
    public ApiResponse<List<RbacResponse.UserRbacDto>> listUsers(@RequestParam(required = false) Long orgId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(permissionService.getAllUsers(orgId));
    }

    // ══════════════════════════════════════════════════════════════════════
    // MENU
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/menu")
    @Operation(summary = "Dynamic menu for the currently authenticated user")
    public ApiResponse<List<RbacResponse.MenuItemDto>> myMenu() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(permissionService.buildMenuForUser(userId));
    }

    /**
     * What this user can do that has no page: Notifications, Global Search.
     *
     * <p>Loaded once beside the menu. Without it the topbar would have to call
     * each feature's own endpoint just to decide whether to draw its button.
     */
    @GetMapping("/capabilities")
    @Operation(summary = "Capability module codes held by the current user",
               description = "Permissions with no page behind them, which is why they are absent "
                           + "from /menu. View is the only right they carry.")
    public ApiResponse<List<String>> myCapabilities() {
        return ApiResponse.success(
                permissionService.capabilitiesForUser(SecurityUtils.getCurrentUserId()));
    }
    @GetMapping("/menu/user/{pidUser}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Dynamic menu for a specific user — admin use")
    public ApiResponse<List<RbacResponse.MenuItemDto>> menuForUser(@PathVariable Long pidUser) {
        return ApiResponse.success(permissionService.buildMenuForUser(pidUser));
    }
}
