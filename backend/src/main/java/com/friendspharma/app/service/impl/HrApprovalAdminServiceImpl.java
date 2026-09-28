package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Configuring approval chains, and the one screen that shows what is waiting.
 *
 * <h3>The inbox is worth having even with no chains at all</h3>
 *
 * <p>{@link #inbox()} answers "what is waiting on me" across every HR workflow,
 * and it does that whether or not a single flow has been configured: with no
 * chains it aggregates the per-module queues that already existed, which were
 * previously only reachable one screen at a time. A manager who has to open six
 * screens to find out whether anything needs signing will not open six screens.
 *
 * <h3>Who may configure a chain</h3>
 *
 * <p>Gated on <b>Approvals (MOD38)</b>, registered by V86: View opens the screen
 * and the inbox, Edit configures who signs what. Who signs is pay policy, and
 * the person who prepares payroll is not the person who sets the rules it runs
 * under — the same separation V77 drew across HR.
 *
 * <p>The inbox then narrows itself again on top of that: an entry only appears
 * where the reader holds the Approve right on the module it belongs to. So View
 * here shows somebody their own queue, never everybody's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrApprovalAdminServiceImpl {

    private final HrApprovalFlowRepository    flows;
    private final HrApprovalRequestRepository requests;
    private final HrEmployeeRepository        employeeRepo;

    private final HrLeaveRequestRepository    leaveRepo;
    private final HrAdvanceRepository         advanceRepo;
    private final HrLoanRepository            loanRepo;
    private final HrOvertimeRequestRepository overtimeRepo;
    private final HrIncrementRepository       incrementRepo;
    private final HrGratuityAwardRepository   gratuityRepo;
    private final HrExpenseClaimRepository    expenseRepo;

    private final HrApprovalService approvals;
    private final PermissionGuard   permissions;
    private final HrScope           scope;

    /* ══ Flows ══════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.ApprovalFlowDto> listFlows() {
        scope.require(HrScope.MOD_APPROVALS, PermissionGuard.Right.VIEW, "see approval flows");
        return flows.findByPidBranchAndStatusOrderByWorkflowCodeAscMinValueAsc(scope.branchId(), "Y")
                .stream().map(this::map).toList();
    }

    @Transactional
    public HrResponses.ApprovalFlowDto saveFlow(Long id, HrRequests.ApprovalFlow req) {
        scope.require(HrScope.MOD_APPROVALS, PermissionGuard.Right.EDIT, "set an approval flow");

        String workflow = req.getWorkflowCode() == null ? "" : req.getWorkflowCode().trim().toUpperCase();
        if (!HrScope.WORKFLOWS.contains(workflow)) {
            throw new BusinessException(
                    "Unknown workflow: " + req.getWorkflowCode() + ". Expected one of "
                    + HrScope.WORKFLOWS + ".",
                    HttpStatus.BAD_REQUEST, "HR_FLOW_WORKFLOW_INVALID");
        }
        if (req.getLevels() == null || req.getLevels().isEmpty()) {
            throw new BusinessException(
                    "A flow with no levels signs nothing. Give it at least one.",
                    HttpStatus.BAD_REQUEST, "HR_FLOW_NO_LEVELS");
        }

        HrApprovalFlow f = id == null
                ? HrApprovalFlow.builder()
                    .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(scope.branchId())
                    .build()
                : flows.findByPidFlowAndPidBranch(id, scope.branchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Approval flow", id));
        if (id == null) f.setStatus("Y");

        f.setWorkflowCode(workflow);
        f.setFlowName(req.getFlowName() == null || req.getFlowName().isBlank()
                ? HrScope.workflowLabel(workflow) : req.getFlowName().trim());
        f.setMinValue(req.getMinValue());
        f.setDescription(trim(req.getDescription()));

        /* Clear, then FLUSH, then insert — uq_hr_flow_level_no is on
           (pid_flow, level_no) and Hibernate orders inserts before deletes, so
           re-saving a flow whose levels keep their numbers would collide. The
           same trap the salary structure's components hit. */
        f.getLevels().clear();
        if (f.getPidFlow() != null) flows.saveAndFlush(f);

        int levelNo = 1;
        Set<String> seen = new LinkedHashSet<>();
        for (HrRequests.ApprovalLevel in : req.getLevels()) {
            String role = in.getApproverRole() == null ? "" : in.getApproverRole().trim().toUpperCase();
            if (role.isBlank()) {
                throw new BusinessException("Every level needs somebody to sign it.",
                        HttpStatus.BAD_REQUEST, "HR_FLOW_LEVEL_ROLE_REQUIRED");
            }
            /* The same signature twice in a row is one signature with extra
               steps, and the second would be unsignable by anybody who had
               already given the first. */
            if (!seen.add(role)) {
                throw new BusinessException(
                        role + " appears twice in this flow. One signature each.",
                        HttpStatus.CONFLICT, "HR_FLOW_LEVEL_DUPLICATE");
            }

            HrApprovalFlowLevel level = HrApprovalFlowLevel.builder()
                    .flow(f)
                    .levelNo(levelNo++)
                    .approverRole(role)
                    .levelLabel(trim(in.getLevelLabel()))
                    .build();
            level.setStatus("Y");
            f.getLevels().add(level);
        }
        flows.save(f);

        log.info("Approval flow saved: {} at branch {}, {} levels from {}",
                 workflow, f.getPidBranch(), f.getLevels().size(), f.getMinValue());
        return map(f);
    }

    /**
     * Retires a flow.
     *
     * <p>Deactivated rather than deleted: requests already travelling through it
     * point at it, and a trail that cannot say which chain it followed is a
     * trail nobody can read. Those requests finish under the levels they
     * started with — {@code TOTAL_LEVELS} was copied onto them.
     */
    @Transactional
    public void retireFlow(Long id) {
        scope.require(HrScope.MOD_APPROVALS, PermissionGuard.Right.EDIT, "retire an approval flow");
        HrApprovalFlow f = flows.findByPidFlowAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Approval flow", id));
        f.setStatus("N");
        flows.save(f);
    }

    /* ══ The inbox ══════════════════════════════════════════════════════ */

    /**
     * Everything waiting on this person, across every HR workflow.
     *
     * <p>Two sources, deliberately merged:
     *
     * <ul>
     *   <li>Objects sitting in a <b>chain</b> whose current level this reader can
     *       sign. The chain says whose turn it is.</li>
     *   <li>Objects merely <b>submitted</b> with no chain at all — the state
     *       every module was in before flows existed, and still is wherever none
     *       is configured. Anybody with the module's Approve right can sign
     *       those.</li>
     * </ul>
     *
     * <p>Merging them is the point. "What needs me" is one question, and
     * answering it out of six screens is why it went unanswered.
     */
    @Transactional(readOnly = true)
    public List<HrResponses.ApprovalInboxDto> inbox() {
        scope.require(HrScope.MOD_APPROVALS, PermissionGuard.Right.VIEW, "see the approvals inbox");
        Long branch = scope.branchId();
        Map<Long, HrEmployee> staff = staffById(branch);

        /* Chains first, so an object in one is described by its chain rather
           than counted twice as a bare submission. */
        Map<String, HrApprovalRequest> chained = new HashMap<>();
        for (HrApprovalRequest r : approvals.pendingAt(branch)) {
            chained.put(r.getWorkflowCode() + "#" + r.getRefId(), r);
        }

        List<HrResponses.ApprovalInboxDto> out = new ArrayList<>();

        collect(out, chained, staff, HrScope.WF_LEAVE, () ->
                leaveRepo.findByPidBranchAndRequestStatusAndStatusOrderByFromDate(
                        branch, HrConstants.LeaveStatus.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidLeaveRequest(), x.getPidEmployee(),
                            "Leave", x.getDays() + " days", x.getFromDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_OVERTIME, () ->
                overtimeRepo.findByPidBranchAndRequestStatusAndStatusOrderByOtDate(
                        branch, HrOvertimeRequest.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidOtRequest(), x.getPidEmployee(),
                            "Overtime", HrAttendance.hours(x.getClaimedMinutes()) + " hours",
                            x.getOtDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_ADVANCE, () ->
                advanceRepo.findByPidBranchAndAdvanceStatusAndStatusOrderByRequestDate(
                        branch, HrConstants.AdvanceStatus.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidAdvance(), x.getPidEmployee(),
                            "Advance", String.valueOf(x.getAmount()),
                            x.getRequestDate() == null ? null : x.getRequestDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_LOAN, () ->
                loanRepo.findByPidBranchAndLoanStatusAndStatusOrderByRequestDate(
                        branch, HrConstants.LoanStatus.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidLoan(), x.getPidEmployee(),
                            "Loan", String.valueOf(x.getPrincipal()),
                            x.getRequestDate() == null ? null : x.getRequestDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_EXPENSE, () ->
                expenseRepo.findByPidBranchAndClaimStatusAndStatusOrderByClaimDate(
                        branch, HrExpenseClaim.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidExpenseClaim(), x.getPidEmployee(),
                            "Expense claim", String.valueOf(x.getClaimedAmount()),
                            x.getClaimDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_INCREMENT, () ->
                incrementRepo.findByPidBranchAndIncrementStatusAndStatusOrderByEffectiveDate(
                        branch, HrIncrement.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidIncrement(), x.getPidEmployee(),
                            "Increment", String.valueOf(x.getIncrementAmount()),
                            x.getEffectiveDate().toString()))
                    .toList());

        collect(out, chained, staff, HrScope.WF_GRATUITY, () ->
                gratuityRepo.findByPidBranchAndAwardStatusAndStatusOrderByServiceTo(
                        branch, HrGratuityAward.SUBMITTED, "Y").stream()
                    .map(x -> new Item(x.getPidGratuityAward(), x.getPidEmployee(),
                            "Gratuity", String.valueOf(x.getGratuityAmount()),
                            x.getServiceTo().toString()))
                    .toList());

        out.sort(Comparator.comparing(HrResponses.ApprovalInboxDto::getWorkflowLabel)
                           .thenComparing(d -> String.valueOf(d.getWhen())));
        return out;
    }

    /** One submitted object, flattened enough for an inbox row. */
    private record Item(Long refId, Long employeeId, String what, String amount, String when) {}

    /**
     * Adds one workflow's queue to the inbox.
     *
     * <p>Skipped entirely where the reader lacks the module's Approve right, so
     * an inbox never advertises a decision somebody cannot make.
     */
    private void collect(List<HrResponses.ApprovalInboxDto> out,
                         Map<String, HrApprovalRequest> chained,
                         Map<Long, HrEmployee> staff,
                         String workflow,
                         java.util.function.Supplier<List<Item>> source) {
        String module = HrScope.moduleOf(workflow);
        if (module == null || !permissions.has(module, PermissionGuard.Right.APPROVE)) return;

        for (Item i : source.get()) {
            HrApprovalRequest chain = chained.get(workflow + "#" + i.refId());
            /* A chain waiting on somebody else is not this reader's to sign, and
               showing it as actionable would be a lie the button then refuses. */
            boolean mine = chain == null || approvals.canSign(chain);
            HrEmployee who = staff.get(i.employeeId());

            out.add(HrResponses.ApprovalInboxDto.builder()
                    .workflowCode(workflow)
                    .workflowLabel(HrScope.workflowLabel(workflow))
                    .refId(i.refId())
                    .employeeId(i.employeeId())
                    .empName(who == null ? null : who.getEmpName())
                    .summary(i.amount())
                    .when(i.when())
                    .chained(chain != null)
                    .progress(chain == null ? null : chain.progress())
                    .flowName(chain == null ? null : chain.getFlowName())
                    .mine(mine)
                    .build());
        }
    }

    /** The chain behind one object, for a screen that wants to show the trail. */
    @Transactional(readOnly = true)
    public List<HrResponses.ApprovalChainDto> chainFor(String workflowCode, Long refId) {
        String module = HrScope.moduleOf(workflowCode);
        if (module != null) {
            scope.require(module, PermissionGuard.Right.VIEW, "see an approval chain");
        }
        return requests.findByWorkflowCodeAndRefIdAndStatusOrderByPidApprovalDesc(
                        workflowCode, refId, "Y")
                .stream().map(this::map).toList();
    }

    /* ══ Mapping ════════════════════════════════════════════════════════ */

    private Map<Long, HrEmployee> staffById(Long branch) {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(branch, "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.ApprovalFlowDto map(HrApprovalFlow f) {
        return HrResponses.ApprovalFlowDto.builder()
                .id(f.getPidFlow())
                .workflowCode(f.getWorkflowCode())
                .workflowLabel(HrScope.workflowLabel(f.getWorkflowCode()))
                .flowName(f.getFlowName())
                .minValue(f.getMinValue())
                .description(f.getDescription())
                .status(f.getStatus())
                .levels(f.getLevels().stream()
                        .map(l -> HrResponses.ApprovalLevelDto.builder()
                                .id(l.getPidFlowLevel())
                                .levelNo(l.getLevelNo())
                                .approverRole(l.getApproverRole())
                                .levelLabel(l.label())
                                .build())
                        .toList())
                .build();
    }

    HrResponses.ApprovalChainDto map(HrApprovalRequest r) {
        return HrResponses.ApprovalChainDto.builder()
                .id(r.getPidApproval())
                .workflowCode(r.getWorkflowCode())
                .workflowLabel(HrScope.workflowLabel(r.getWorkflowCode()))
                .refId(r.getRefId())
                .employeeId(r.getPidEmployee())
                .flowName(r.getFlowName())
                .routedValue(r.getRoutedValue())
                .currentLevel(r.getCurrentLevel())
                .totalLevels(r.getTotalLevels())
                .progress(r.progress())
                .requestStatus(r.getRequestStatus())
                .requestStatusLabel(HrConstants.label(r.getRequestStatus()))
                .startedAt(r.getStartedAt())
                .closedAt(r.getClosedAt())
                .actions(r.getActions().stream()
                        .map(a -> HrResponses.ApprovalActionDto.builder()
                                .levelNo(a.getLevelNo())
                                .levelLabel(a.getLevelLabel())
                                .decision(a.getDecision())
                                .decidedBy(a.getDecidedBy())
                                .decidedAt(a.getDecidedAt())
                                .remarks(a.getRemarks())
                                .build())
                        .toList())
                .build();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
