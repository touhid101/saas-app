package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may work the E-Commerce Customers console.
 *
 * <p>Two tests, both required. The customer is global — outside every
 * organisation — so deciding on one is a <b>platform</b> action: ROLE01 in the
 * platform organisation, the same test {@code ActingBranchFilter} applies.
 * And the matrix still has to say so: MOD55 with the right in question, so a
 * pharmacy that somehow holds ROLE01 in the platform org can still be narrowed
 * on the RBAC screen. The role alone is not enough; the org alone is not
 * enough (memory: platform operator = role AND org).
 */
@Service
@RequiredArgsConstructor
public class EcomScope {

    public static final String MOD_CUSTOMERS = "MOD55";

    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;

    @Transactional(readOnly = true)
    public void require(PermissionGuard.Right right, String action) {
        if (!branchScope.isPlatformOperator()) {
            throw new BusinessException(
                    "Only a platform administrator can " + action + ". Online customers belong to "
                    + "the platform, not to one pharmacy.",
                    HttpStatus.FORBIDDEN, "NOT_PLATFORM_OPERATOR");
        }
        if (!permissions.hasByRole(MOD_CUSTOMERS, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + ". Ask somebody whose role holds "
                    + label(right) + " on E-Commerce Customers.",
                    HttpStatus.FORBIDDEN, "ECOM_NOT_PERMITTED");
        }
    }

    private static String label(PermissionGuard.Right r) {
        return switch (r) {
            case VIEW -> "View"; case CREATE -> "Create"; case EDIT -> "Edit"; case DELETE -> "Delete";
            case APPROVE -> "Approve"; case EXPORT -> "Export"; case PRINT -> "Print";
        };
    }
}
