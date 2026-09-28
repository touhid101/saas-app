package com.friendspharma.app.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Utility class for Spring Security operations
 */
public class SecurityUtils {

    /**
     * Get the currently authenticated user's ID
     * @return User ID or null if not authenticated
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        
        // If principal is our UserPrincipal implementation
        if (principal instanceof UserPrincipal) {
            return ((UserPrincipal) principal).getUserId();
        }
        
        // If principal is a username string (shouldn't happen with JWT)
        if (principal instanceof String) {
            // Try to parse as Long
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }

    /**
     * Get the currently authenticated username
     * @return Username or null if not authenticated
     */
    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        }
        
        if (principal instanceof String) {
            return (String) principal;
        }
        
        return null;
    }

    /**
     * Check if current user has a specific role
     * @param role Role name (e.g., "ADMIN", "USER")
     * @return true if user has the role
     */
    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_" + role) || 
                                 auth.getAuthority().equals(role));
    }
    
    /**
     * Get the current UserPrincipal
     * @return UserPrincipal or null if not authenticated
     */
    public static UserPrincipal getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        
        if (principal instanceof UserPrincipal) {
            return (UserPrincipal) principal;
        }
        
        return null;
    }
    
    /**
     * Get the current user's organization ID
     * @return Organization ID or null if not authenticated
     */
    public static Long getCurrentOrgId() {
        // A platform operator acting as a pharmacy is inside that pharmacy
        // entirely. Returning their own organisation here would leave every
        // query filtering on the wrong one and answering with an empty shop.
        Long acting = ActingBranch.getOrg();
        if (acting != null) return acting;
        UserPrincipal user = getCurrentUser();
        return user != null ? user.getOrgId() : null;
    }

    /**
     * The organisation the signed-in user really belongs to, ignoring any
     * pharmacy they are acting as.
     *
     * <p>Used by the checks that decide whether somebody MAY act. Reading the
     * acted-as organisation there would make the test pass by virtue of having
     * already passed it, which is not a test.
     */
    public static Long getRealOrgId() {
        UserPrincipal p = getCurrentUser();
        return p == null ? null : p.getOrgId();
    }

    
    /**
     * Get the current user's company ID
     * @return Company ID or null if not authenticated
     */
    public static Long getCurrentCompanyId() {
        UserPrincipal user = getCurrentUser();
        return user != null ? user.getCompanyId() : null;
    }
    
    /**
     * Get the current user's branch ID
     * @return Branch ID or null if not authenticated
     */
    public static Long getCurrentBranchId() {
        UserPrincipal user = getCurrentUser();
        return user != null ? user.getBranchId() : null;
    }
    
    /**
     * Get the current user's role code
     * @return Role code or null if not authenticated
     */
    public static String getCurrentRoleCode() {
        UserPrincipal user = getCurrentUser();
        return user != null ? user.getRoleCode() : null;
    }

    /** ROLE06 — Delivery Staff. A driver, not an office user. */
    public static final String ROLE_DELIVERY = "ROLE06";

    /**
     * The staff record behind the signed-in user, or null for an office login.
     *
     * <p>This is what matches a driver to the invoices dispatched to them:
     * {@code POS_TRANSACTION_MST.DELIVERY_MAN} holds an employee id, not a user
     * id. Read fresh from the database on every request, so re-linking an
     * account takes effect without the user signing in again.
     */
    public static Long getCurrentEmployeeId() {
        UserPrincipal user = getCurrentUser();
        return user != null ? user.getEmployeeId() : null;
    }

    /**
     * True when the caller is delivery staff and nothing more.
     *
     * <p>Super admins are excluded deliberately: an admin who also holds ROLE06
     * should still see the whole branch rather than being narrowed to their own
     * rounds. The narrowing is a restriction, not a preference.
     */
    public static boolean isDeliveryStaff() {
        return !isSuperAdmin() && ROLE_DELIVERY.equals(getCurrentRoleCode());
    }
    
    /**
     * Check if current user is super admin
     * @return true if super admin
     */
    public static boolean isSuperAdmin() {
        UserPrincipal user = getCurrentUser();
        return user != null && user.isSuperAdmin();
    }
}
