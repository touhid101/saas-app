package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class RbacResponse {

    @Data @Builder
    public static class ModuleDto {
        private Long pidModule;
        private String moduleCode;
        private String moduleName;
        private String moduleDesc;
        private String iconKey;
        private String routePath;
        private Long pidParentModule;
        private Integer sortOrder;
        private Integer isActive;
        private List<ModuleDto> children;

        /** How many roles can reach this page. Zero means nobody can. */
        private Long roleCount;
    }

    @Data @Builder
    public static class PermissionDto {
        private Long pidPermission;
        private Long pidRole;
        private String roleName;
        private Long pidModule;
        private String moduleName;
        private String moduleCode;
        private String iconKey;
        private Integer canView;
        private Integer canCreate;
        private Integer canEdit;
        private Integer canDelete;
        private Integer canExport;
        private Integer canPrint;
        private Integer canApprove;
    }

    @Data @Builder
    public static class RoleDto {
        private Long pidGroup;
        private String groupName;
        private String groupCode;
        private String description;
        private String roleColor;
        private Integer isActive;
        private LocalDateTime createdAt;
        private List<PermissionDto> permissions;
    }

    @Data @Builder
    public static class UserRbacDto {
        private Long pidUser;
        private String username;
        private String fullName;
        private String email;
        private String avatarUrl;
        private Long pidGroup;
        private String groupName;
        /** Which branch this login belongs to — and therefore all it can see. */
        private Long pidBranch;
        private String branchName;
        private Integer isActive;
        private LocalDateTime createdAt;

        /**
         * The staff record this login belongs to.
         *
         * <p>Required for delivery staff: it is how a signed-in driver is
         * matched to the invoices dispatched to them, which carry an employee
         * id rather than a user id.
         */
        private Long pidEmployee;
        private String employeeName;
        private String employeeType;

        private List<PermissionDto> permissions;   // effective permissions from their role
    }

    /** Returned to the frontend to build the dynamic menu */
    @Data @Builder
    public static class MenuItemDto {
        private Long pidModule;
        private String moduleCode;
        private String moduleName;
        private String iconKey;
        private String routePath;
        private Integer sortOrder;
        private boolean canView;
        private boolean canCreate;
        private boolean canEdit;
        private boolean canDelete;
        private boolean canExport;
        private boolean canPrint;
        private boolean canApprove;
        private List<MenuItemDto> children;
    }
}
