package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

public class RbacRequest {

    @Data
    public static class CreateModuleRequest {
        private String moduleCode;  // Optional - auto-generated if not provided
        @NotBlank private String moduleName;
        private String moduleDesc;
        private String iconKey;
        private String routePath;
        private Long pidParentModule;
        private Integer sortOrder;
    }

    @Data
    public static class UpdateModuleRequest {
        private String moduleName;
        private String moduleDesc;
        private String iconKey;
        private String routePath;
        private Integer sortOrder;
        private Integer isActive;

        /**
         * Move this module under a different menu group, or to the top level.
         *
         * <p>Absent leaves the parent alone; present-and-null moves it to the
         * top. Those are different intentions and a plain null cannot express
         * both, so {@code clearParent} says which is meant.
         */
        private Long pidParentModule;
        private Boolean clearParent;

        /** Present-and-blank makes this a menu group, which has no page. */
        private Boolean clearRoutePath;
    }

    @Data
    public static class CreateRoleRequest {
        @NotBlank private String groupName;
        private String groupCode;  // Optional - auto-generated if not provided
        private String description;
        private String roleColor;  // Hex color for role badge (e.g., #2E6A62)
    }

    @Data
    public static class UpdateRoleRequest {
        private String groupName;
        private String description;
        private String roleColor;  // Hex color for role badge
        private Integer isActive;
    }

    @Data
    public static class PermissionEntry {
        @NotNull private Long pidModule;
        @NotNull private Integer canView;
        @NotNull private Integer canCreate;
        @NotNull private Integer canEdit;
        @NotNull private Integer canDelete;
        @NotNull private Integer canExport;
        @NotNull private Integer canApprove;
    }

    @Data
    public static class SetRolePermissionsRequest {
        @Valid
        @NotEmpty
        private List<PermissionEntry> permissions;
    }

    @Data
    public static class CreateUserRequest {
        @NotBlank private String username;
        @NotBlank private String password;
        @NotBlank private String fullName;
        private String email;
        private String phoneNo;
        @NotNull private Long pidGroup;
        /**
         * Accepted and ignored.
         *
         * <p>{@code createUser} takes the organisation from the caller's session,
         * never from here — an organisation in a request body is an organisation a
         * caller can change. It was @NotNull for a while, which made the API demand
         * a value it then threw away and rejected every console that did not guess
         * it.
         */
        private Long pidOrg;
        /**
         * The branch this login works at, and the only branch it will see.
         *
         * <p>Required. Every operational screen is scoped to the signed-in
         * user's branch, so an account created without one can sign in and
         * then read nothing at all — which looks like a broken system rather
         * than a half-filled form.
         */
        @NotNull(message = "Choose the branch this user works at.")
        private Long pidBranch;
        private String avatarUrl;
        /**
         * The staff record this login belongs to.
         *
         * <p>Required for delivery staff (ROLE06): an invoice records who it was
         * dispatched to as an employee id, so without this a signed-in driver
         * cannot be matched to their own rounds. Null for office logins.
         */
        private Long pidEmployee;
    }

    @Data
    public static class UpdateUserRequest {
        private String fullName;
        private String email;
        private String phoneNo;
        private Long pidGroup;
        /**
         * Moves the login to another branch. Null leaves it where it is.
         *
         * <p>There was no way to set this after creation, so a user created
         * against the wrong branch — or before the branch was required — could
         * never be repaired through the application.
         */
        private Long pidBranch;
        private Integer isActive;
        private String avatarUrl;
        /**
         * The staff record this login belongs to.
         *
         * <p>Required for delivery staff (ROLE06): an invoice records who it was
         * dispatched to as an employee id, so without this a signed-in driver
         * cannot be matched to their own rounds. Null for office logins.
         */
        private Long pidEmployee;
    }

    @Data
    public static class ChangePasswordRequest {
        /** Current password — required when a user changes their own password.
         *  Admins resetting someone else's password may omit it. */
        private String oldPassword;

        @NotBlank(message = "New password is required")
        @Size(min = 6, max = 100, message = "New password must be between 6 and 100 characters")
        private String newPassword;

        /** Accepts {"currentPassword": ...} as an alias for {"oldPassword": ...}. */
        public void setCurrentPassword(String currentPassword) {
            this.oldPassword = currentPassword;
        }
    }
}
