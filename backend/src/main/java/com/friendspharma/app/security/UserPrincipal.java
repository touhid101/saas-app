package com.friendspharma.app.security;

import lombok.Builder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security UserDetails wrapper around ACL_USER.
 * Carries org/branch/role context scoped from the authenticated user.
 */
@Builder
public class UserPrincipal implements UserDetails {

    private final Long    userId;
    private final String  username;
    private final String  password;
    private final String  fullName;
    private final Long    orgId;
    private final Long    companyId;
    private final Long    branchId;
    /** The staff record behind this login. Null unless the user is an employee. */
    private final Long    employeeId;
    private final String  roleCode;
    private final String  roleName;
    private final boolean superAdmin;
    private final String  status;

    // ── Explicit getters (no @Getter to avoid conflict with UserDetails) ──

    public Long    getUserId()    { return userId; }
    public String  getFullName()  { return fullName; }
    public Long    getOrgId()     { return orgId; }
    public Long    getCompanyId() { return companyId; }
    public Long    getBranchId()  { return branchId; }
    public Long    getEmployeeId() { return employeeId; }
    public String  getRoleCode()  { return roleCode; }
    public String  getRoleName()  { return roleName; }
    public boolean isSuperAdmin() { return superAdmin; }

    // ── UserDetails implementation ─────────────────────────────────────────

    @Override public String getUsername()  { return username; }
    @Override public String getPassword()  { return password; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String code = (roleCode != null && !roleCode.isBlank()) ? roleCode.toUpperCase() : "VIEWER";

        if (superAdmin || "ROLE01".equals(code)) {
            // Super Admin gets:
            //  ROLE_ADMIN      → satisfies hasRole('ADMIN') used in RbacController
            //  ROLE_SUPER_ADMIN → semantic marker
            //  ROLE_ROLE01..ROLE_ROLE09 → satisfies hasAnyRole('ROLE01','ROLE02',...) in MasterDataController
            //  ROLE_<their actual code> → redundant for ROLE01 but explicit for clarity
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
            // Grant all role codes so super admin passes any hasAnyRole(...) check
            for (int i = 1; i <= 9; i++) {
                authorities.add(new SimpleGrantedAuthority(String.format("ROLE_ROLE%02d", i)));
            }
            return authorities;
        }

        // Regular users: grant their specific role code
        // hasAnyRole('ROLE01') checks for ROLE_ROLE01 → this matches
        return List.of(new SimpleGrantedAuthority("ROLE_" + code));
    }

    @Override public boolean isAccountNonExpired()    { return true; }
    @Override public boolean isAccountNonLocked()     {
        return !"I".equals(status) && !"N".equals(status);
    }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() {
        return "Y".equals(status) || "Active".equalsIgnoreCase(status) || status == null;
    }
}
