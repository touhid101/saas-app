package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where HR gets its branch from, and how it asks whether the caller may act.
 *
 * <p>One bean rather than the same twenty lines in five services. Every HR
 * service needs the same three things — the session's organisation, the
 * session's branch, and a permission check against its own module — and five
 * copies of that is five places for one of them to be forgotten.
 *
 * <h3>The rules it enforces</h3>
 *
 * <p><b>Scope comes from the session, never from a payload.</b> No HR request
 * carries a branch id. A branch in a request body is a branch a client can
 * change, and this product's whole tenancy model rests on it not being one.
 *
 * <p><b>Authorisation is the permission matrix.</b> Not a role name in an
 * annotation. Granting a right on the RBAC screen grants it in the application,
 * for any role, with no code change — see {@link PermissionGuard}. HR modules
 * were registered by V77; the codes are the constants below.
 *
 * <p><b>An employee from another branch is not found, not refused.</b> Saying
 * "you may not touch employee 47" confirms that employee 47 exists, which is
 * more than a caller at another pharmacy should be able to learn.
 */
@Service
@RequiredArgsConstructor
public class HrScope {

    /** Employees — the HR home screen. */
    public static final String MOD_EMPLOYEE   = "MOD32";
    public static final String MOD_ATTENDANCE = "MOD33";
    public static final String MOD_LEAVE      = "MOD34";
    /** Advance and loan. */
    public static final String MOD_ADVANCE    = "MOD35";
    public static final String MOD_PAYROLL    = "MOD36";
    /**
     * Expense and conveyance claims (V83).
     *
     * <p>A module of its own, unlike the provident fund and gratuity, which sit
     * under Payroll and Employees. A claim is something ordinary staff file for
     * themselves, so a pharmacy has to be able to let everybody claim without
     * letting everybody see payroll.
     */
    public static final String MOD_EXPENSE    = "MOD37";
    /** The Approvals screen and the chains behind it (V86). */
    public static final String MOD_APPROVALS  = "MOD38";
    /** V87: every hr- report, moved off the general Reports screen. */
    public static final String MOD_HR_REPORTS = "MOD39";
    /** V87: departments and designations, moved off Master Data. */
    public static final String MOD_HR_MASTER  = "MOD40";

    /* ── The workflows a multi-level approval chain can be configured for ──
     *
     * Their own vocabulary rather than the module codes above, because two
     * workflows can share a module: increments and gratuity awards both sit
     * under Employees, and a pharmacy that wants two signatures on a pay rise
     * does not thereby want two on a leaving payment.
     *
     * Authorisation is still the module's Approve right. A chain only narrows
     * who may give it — see {@link HrApprovalService}.
     *
     * Payroll is absent on purpose: a run already has five signatures of its
     * own, and a second chain around them would give it two answers to "who
     * signs next". */
    public static final String WF_LEAVE     = "LEAVE";
    public static final String WF_ADVANCE   = "ADVANCE";
    public static final String WF_LOAN      = "LOAN";
    public static final String WF_OVERTIME  = "OVERTIME";
    public static final String WF_INCREMENT = "INCREMENT";
    public static final String WF_GRATUITY  = "GRATUITY";
    public static final String WF_EXPENSE   = "EXPENSE";

    /** Every workflow a flow may be defined for, in the order a screen lists them. */
    public static final java.util.List<String> WORKFLOWS = java.util.List.of(
            WF_LEAVE, WF_OVERTIME, WF_ADVANCE, WF_LOAN,
            WF_EXPENSE, WF_INCREMENT, WF_GRATUITY);

    /** The module whose Approve right a workflow's decisions are gated on. */
    public static String moduleOf(String workflowCode) {
        return switch (workflowCode == null ? "" : workflowCode) {
            case WF_LEAVE     -> MOD_LEAVE;
            case WF_OVERTIME  -> MOD_ATTENDANCE;
            case WF_ADVANCE, WF_LOAN -> MOD_ADVANCE;
            case WF_EXPENSE   -> MOD_EXPENSE;
            case WF_INCREMENT, WF_GRATUITY -> MOD_EMPLOYEE;
            default           -> null;
        };
    }

    /** What a screen calls a workflow. */
    public static String workflowLabel(String workflowCode) {
        return switch (workflowCode == null ? "" : workflowCode) {
            case WF_LEAVE     -> "Leave";
            case WF_OVERTIME  -> "Overtime";
            case WF_ADVANCE   -> "Advance";
            case WF_LOAN      -> "Loan";
            case WF_EXPENSE   -> "Expense claim";
            case WF_INCREMENT -> "Increment";
            case WF_GRATUITY  -> "Gratuity";
            default           -> workflowCode;
        };
    }

    private final PermissionGuard      permissions;
    private final BranchScopeService   branchScope;
    private final AllBranchRepository  branchRepo;
    private final HrEmployeeRepository employeeRepo;

    /* ── Scope ──────────────────────────────────────────────────────────── */

    public Long orgId() {
        return SecurityUtils.getCurrentOrgId();
    }

    /**
     * The branch every HR read and write is bounded to.
     *
     * <p>Delegates to {@link BranchScopeService}, which refuses rather than
     * widening when it cannot decide — a query that silently falls back to
     * "every branch" is the failure that class exists to prevent.
     */
    public Long branchId() {
        return branchScope.resolve(null);
    }

    /**
     * The company the session's branch belongs to.
     *
     * <p>Read from the branch rather than taken from a request, for the same
     * reason the branch is: it has to agree with the branch anyway, and a
     * company in a payload is a company the caller can change.
     */
    @Transactional(readOnly = true)
    public Long companyId() {
        Long branch = branchId();
        if (branch == null) return null;
        return branchRepo.findById(branch).map(AllBranch::getPidCompany).orElse(null);
    }

    /* ── Self-service ───────────────────────────────────────────────────── */

    /**
     * The employee the current thread is acting <em>as</em>, when it is acting
     * through the self-service portal — null everywhere else.
     *
     * <p>An employee filing their own leave holds no right on the Leave module:
     * those rights are for the people who run HR, and granting them would let
     * a clerk file leave for anybody. Self-service is authority of a different
     * kind — <b>over one's own records only</b> — and it is expressed here as
     * a thread-local set by {@code HrSelfServiceImpl} around each call, after
     * it has resolved the signed-in user to their employee and, for anything
     * addressed by id, checked that the record is theirs.
     *
     * <p>While set, {@link #require} passes every right except APPROVE. It is
     * never set around an approve, reject or pay; the self-service layer calls
     * only file, read, attach and cancel, and it checks ownership first. The
     * flag is cleared in a finally, so a failure cannot leave a thread acting
     * as somebody.
     */
    private static final ThreadLocal<Long> SELF = new ThreadLocal<>();

    /** Runs {@code work} as the given employee's own self-service. */
    public <T> T asSelf(Long employeeId, java.util.function.Supplier<T> work) {
        if (employeeId == null) {
            throw new BusinessException(
                    "Your login is not linked to an employee record. Ask HR to link it.",
                    HttpStatus.FORBIDDEN, "HR_NOT_AN_EMPLOYEE");
        }
        Long before = SELF.get();
        SELF.set(employeeId);
        try { return work.get(); }
        finally { if (before == null) SELF.remove(); else SELF.set(before); }
    }

    /** The employee this thread is acting as through self-service, or null. */
    public Long selfEmployeeId() { return SELF.get(); }

    /* ── Permission ─────────────────────────────────────────────────────── */

    public boolean has(String moduleCode, PermissionGuard.Right right) {
        return permissions.has(moduleCode, right);
    }

    /**
     * Refuses unless the caller holds {@code right} on {@code moduleCode}.
     *
     * <p>The message names the right and the screen, because "forbidden" tells
     * a user nothing they can act on, while "ask somebody whose role holds
     * Approve on Payroll" tells them exactly who to find.
     */
    public void require(String moduleCode, PermissionGuard.Right right, String action) {
        // Self-service: one's own records, never a decision on them.
        if (SELF.get() != null && right != PermissionGuard.Right.APPROVE) return;
        if (!permissions.has(moduleCode, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + ". "
                    + "Ask somebody whose role holds " + label(right)
                    + " on " + screen(moduleCode) + ".",
                    HttpStatus.FORBIDDEN, "HR_NOT_PERMITTED");
        }
    }

    /* ── Employees, always inside the branch ────────────────────────────── */

    /**
     * An employee of the session's branch.
     *
     * <p>Not found rather than refused when they belong elsewhere — see the
     * class comment.
     */
    @Transactional(readOnly = true)
    public HrEmployee employee(Long employeeId) {
        if (employeeId == null) {
            throw new BusinessException("Which employee?", HttpStatus.BAD_REQUEST, "EMPLOYEE_REQUIRED");
        }
        HrEmployee e = employeeRepo.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        Long branch = branchId();
        if (branch != null && !branch.equals(e.getPidBranch())) {
            throw new ResourceNotFoundException("Employee", employeeId);
        }
        return e;
    }

    /** As {@link #employee}, and refuses somebody who has left. */
    @Transactional(readOnly = true)
    public HrEmployee activeEmployee(Long employeeId) {
        HrEmployee e = employee(employeeId);
        if (!e.isActive()) {
            throw new BusinessException(
                    e.getEmpName() + " is no longer active. Reactivate the employee first.",
                    HttpStatus.CONFLICT, "EMPLOYEE_INACTIVE");
        }
        return e;
    }

    /** Who is doing this, for the audit stamps the entities carry themselves. */
    public String actor() {
        String user = SecurityUtils.getCurrentUsername();
        return user == null ? "SYSTEM" : user;
    }

    /* ── Wording ────────────────────────────────────────────────────────── */

    private static String label(PermissionGuard.Right right) {
        return switch (right) {
            case VIEW    -> "View";
            case CREATE  -> "Add";
            case EDIT    -> "Edit";
            case DELETE  -> "Delete";
            case APPROVE -> "Approve";
            case EXPORT  -> "Export";
            case PRINT   -> "Print";
        };
    }

    private static String screen(String moduleCode) {
        return switch (moduleCode) {
            case MOD_EMPLOYEE   -> "Employees";
            case MOD_ATTENDANCE -> "Attendance";
            case MOD_LEAVE      -> "Leave";
            case MOD_ADVANCE    -> "Advance & Loan";
            case MOD_PAYROLL    -> "Payroll";
            case MOD_EXPENSE    -> "Expense Claims";
            case MOD_APPROVALS  -> "Approvals";
            case MOD_HR_REPORTS -> "HR Reports";
            case MOD_HR_MASTER  -> "HR Master";
            default             -> moduleCode;
        };
    }
}
