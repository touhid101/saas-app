package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Who may do what in Accounts, and which books they are in.
 *
 * <p>The HR module's {@code HrScope} shape, for the same reason: every
 * accounting service asks one object for the organisation, the branch and the
 * permission, so the answers cannot drift between services. Authorisation is
 * the permission matrix only — no role names in code (V93 registers the rights).
 *
 * <p>The organisation is the set of books; the branch is a dimension of every
 * journal line. A pharmacy user is bounded to the branch in their session, as
 * everywhere else in the application; a platform operator may name a branch.
 */
@Service
@RequiredArgsConstructor
public class AccScope {

    public static final String MOD_DASHBOARD    = "MOD41";
    public static final String MOD_JOURNALS     = "MOD42";
    public static final String MOD_LEDGER       = "MOD43";
    public static final String MOD_COA          = "MOD44";
    public static final String MOD_PARTIES      = "MOD45";
    public static final String MOD_CASH_BANK    = "MOD46";
    public static final String MOD_DAILY_CASH   = "MOD47";
    public static final String MOD_INVESTORS    = "MOD48";
    public static final String MOD_PROFIT       = "MOD49";
    public static final String MOD_SETTINGS     = "MOD50";
    public static final String MOD_REPORTS      = "MOD51";
    public static final String MOD_RECONCILE    = "MOD52";

    private static final Map<String, String> SCREENS = Map.ofEntries(
            Map.entry(MOD_DASHBOARD, "Accounts Dashboard"), Map.entry(MOD_JOURNALS, "Journals"),
            Map.entry(MOD_LEDGER, "General Ledger"),        Map.entry(MOD_COA, "Chart of Accounts"),
            Map.entry(MOD_PARTIES, "Receivables & Payables"), Map.entry(MOD_CASH_BANK, "Cash & Bank"),
            Map.entry(MOD_DAILY_CASH, "Daily Cash"),        Map.entry(MOD_INVESTORS, "Investors"),
            Map.entry(MOD_PROFIT, "Profit Distribution"),   Map.entry(MOD_SETTINGS, "Periods & Settings"),
            Map.entry(MOD_REPORTS, "Accounts Reports"),     Map.entry(MOD_RECONCILE, "Reconciliation"));

    private final PermissionGuard    permissions;
    private final BranchScopeService branchScope;

    public Long orgId() {
        Long org = SecurityUtils.getCurrentOrgId();
        if (org == null) {
            throw new BusinessException("Your account has no organisation assigned, so it has no books to work in.",
                    HttpStatus.BAD_REQUEST, "NO_ORG_CONTEXT");
        }
        return org;
    }

    /** The session branch, or — for a platform operator — the branch they named. */
    public Long branchId(Long requested) {
        Long b = branchScope.resolve(requested);
        if (b == null) {
            throw new BusinessException("Your account has no branch assigned, so nothing can be booked.",
                    HttpStatus.BAD_REQUEST, "NO_BRANCH_CONTEXT");
        }
        return b;
    }

    public Long branchId() { return branchId(null); }

    /** Null when the caller may see every branch of the organisation. */
    public Long branchFilter(Long requested) {
        if (branchScope.isPlatformOperator()) return requested;
        return branchId(requested);
    }

    public boolean isPlatformOperator() { return branchScope.isPlatformOperator(); }

    public boolean has(String moduleCode, PermissionGuard.Right right) {
        return permissions.has(moduleCode, right);
    }

    public void require(String moduleCode, PermissionGuard.Right right, String action) {
        if (!permissions.has(moduleCode, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + ". Ask somebody whose role holds "
                    + label(right) + " on " + SCREENS.getOrDefault(moduleCode, moduleCode) + ".",
                    HttpStatus.FORBIDDEN, "ACC_NOT_PERMITTED");
        }
    }

    /** Any Accounts screen at all — what the dashboard and shared lookups need. */
    public void requireAnyView(String action) {
        for (String m : SCREENS.keySet()) if (permissions.has(m, PermissionGuard.Right.VIEW)) return;
        throw new BusinessException("You do not have permission to " + action + ".", HttpStatus.FORBIDDEN, "ACC_NOT_PERMITTED");
    }

    public String username() { return SecurityUtils.getCurrentUsername(); }

    private static String label(PermissionGuard.Right r) {
        return switch (r) {
            case VIEW -> "View"; case CREATE -> "Create"; case EDIT -> "Edit"; case DELETE -> "Delete";
            case APPROVE -> "Approve"; case EXPORT -> "Export"; case PRINT -> "Print";
        };
    }
}
