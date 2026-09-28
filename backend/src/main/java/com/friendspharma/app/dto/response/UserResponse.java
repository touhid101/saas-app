package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserResponse {
    private Long userId;
    private String username;
    private String fullName;
    private String email;
    private String mobileNo;
    private Long orgId;
    private Long companyId;
    private Long branchId;
    private String roleCode;
    private String roleName;
    private String roleColor;
    private boolean superAdmin;
    private String status;
    private String avatarUrl;
    /** The employee record this login is linked to, if any — what unlocks self-service. */
    private Long employeeId;
    private LocalDateTime lastLogin;
    private String branchName;
    /** The pharmacy's own logo, or null. Shown in the sidebar and the favicon. */
    private String branchLogoUrl;
}
