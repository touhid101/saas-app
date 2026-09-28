package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.response.RbacResponse;

import java.util.List;

public interface PermissionService {

    // ── Modules ────────────────────────────────────────────────────────────

    RbacResponse.ModuleDto createModule(RbacRequest.CreateModuleRequest req);
    RbacResponse.ModuleDto updateModule(Long pidModule, RbacRequest.UpdateModuleRequest req);
    void deleteModule(Long pidModule);
    RbacResponse.ModuleDto getModuleById(Long pidModule);
    List<RbacResponse.ModuleDto> getAllModules(boolean activeOnly);
    /** Returns tree structure (children nested under parents) */
    List<RbacResponse.ModuleDto> getModuleTree(boolean activeOnly);

    // ── Roles ──────────────────────────────────────────────────────────────

    RbacResponse.RoleDto createRole(RbacRequest.CreateRoleRequest req);
    RbacResponse.RoleDto updateRole(Long pidGroup, RbacRequest.UpdateRoleRequest req);
    void deleteRole(Long pidGroup);
    RbacResponse.RoleDto getRoleById(Long pidGroup);
    List<RbacResponse.RoleDto> getAllRoles(boolean withPermissions);

    // ── Permissions ────────────────────────────────────────────────────────

    /** Replace all permissions for a role */
    List<RbacResponse.PermissionDto> setRolePermissions(Long pidGroup,
                                                          RbacRequest.SetRolePermissionsRequest req);

    List<RbacResponse.PermissionDto> getRolePermissions(Long pidGroup);

    /* The same data read down the other axis: one module, every role. */
    java.util.List<java.util.Map<String, Object>> getModuleRoles(Long pidModule);

    int setModuleRoles(Long pidModule, java.util.List<java.util.Map<String, Object>> rows);

    // ── Users ──────────────────────────────────────────────────────────────

    RbacResponse.UserRbacDto createUser(RbacRequest.CreateUserRequest req);
    RbacResponse.UserRbacDto updateUser(Long pidUser, RbacRequest.UpdateUserRequest req);
    RbacResponse.UserRbacDto uploadUserAvatar(Long pidUser, org.springframework.web.multipart.MultipartFile file);
    void changePassword(Long pidUser, RbacRequest.ChangePasswordRequest req);
    void toggleUserActive(Long pidUser, boolean active);
    RbacResponse.UserRbacDto getUserById(Long pidUser);
    List<RbacResponse.UserRbacDto> getAllUsers(Long orgId);

    // ── Menu builder (called from auth flow) ──────────────────────────────

    List<RbacResponse.MenuItemDto> buildMenuForUser(Long pidUser);

    /** Capability module codes the user holds — permissions with no page. */
    List<String> capabilitiesForUser(Long pidUser);
}
