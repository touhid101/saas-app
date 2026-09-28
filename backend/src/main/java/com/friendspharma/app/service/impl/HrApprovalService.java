package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.HrApprovalFlowRepository;
import com.friendspharma.app.repository.HrApprovalRequestRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The multi-level approval chain, consulted by the workflows that already work.
 *
 * <h3>How a service uses it</h3>
 *
 * <p>Three lines, in the same three places, in every module that routes through
 * it:
 *
 * <pre>
 *   submit():   approvals.begin(WORKFLOW, id, employee, value);
 *
 *   approve():  var step = approvals.decide(WORKFLOW, id, true, remarks);
 *               if (step == Step.PENDING_NEXT) return map(...);   // still SUBMITTED
 *               ... everything the method already did, unchanged ...
 *
 *   cancel():   approvals.cancelFor(WORKFLOW, id);
 * </pre>
 *
 * <p><b>With no flow defined, {@code decide} returns {@link Step#NO_FLOW}
 * immediately and nothing is written.</b> That is how every branch starts and
 * how they all stay until somebody configures a chain: one Approve right, one
 * decision, exactly as before this class existed. The same compatibility
 * contract as the working policy, the PF scheme and the gratuity formula.
 *
 * <h3>What a level can and cannot do</h3>
 *
 * <p>A level <b>narrows</b> who may sign. The module's Approve right is checked
 * by the calling service before this class is ever reached, so a flow can never
 * hand somebody an authority the RBAC screen did not give them — it can only
 * take one away, which is what a chain is for.
 *
 * <p>{@code MANAGER} means the employee's own manager, read from
 * {@code HR_EMPLOYEES.PID_MANAGER}. Where the employee has no manager on record
 * the level is <b>not</b> silently skipped: it refuses and says so, because
 * quietly dropping a signature is exactly the failure a chain exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrApprovalService {

    /** What a service should do next, having asked. */
    public enum Step {
        /** No chain applies. Carry on exactly as before. */
        NO_FLOW,
        /** Recorded, and somebody else signs next. The object stays submitted. */
        PENDING_NEXT,
        /** The last signature. Complete the approval as usual. */
        FINAL,
        /** Refused at this level. Mark the object rejected as usual. */
        REJECTED
    }

    private final HrApprovalFlowRepository    flows;
    private final HrApprovalRequestRepository requests;
    private final HrEmployeeRepository        employees;
    private final HrScope                     scope;

    /* ══ Starting a chain ═══════════════════════════════════════════════ */

    /**
     * Starts a chain if one is configured, and does nothing if not.
     *
     * @param workflowCode which chain this is — LEAVE, ADVANCE, EXPENSE, …
     * @param refId      the object's own id
     * @param employee   whose request it is; read to find their manager
     * @param value      what routes it, in whatever the module measures itself
     *                   in — days for leave, money for an advance or a claim.
     *                   Only the caller knows which, so only the caller supplies
     *                   it. Null routes to the flow with no threshold.
     * @return the chain, or empty when no flow applies
     */
    @Transactional
    public Optional<HrApprovalRequest> begin(String workflowCode, Long refId,
                                             HrEmployee employee, BigDecimal value) {
        if (workflowCode == null || refId == null || employee == null) return Optional.empty();

        /* A chain left over from an earlier attempt would make the object wait
           on a level nobody is looking at. Closing it is not losing anything:
           the trail rows stay. */
        requests.findLive(workflowCode, refId).ifPresent(live -> {
            live.setRequestStatus(HrApprovalRequest.CANCELLED);
            live.setClosedBy(scope.actor());
            live.setClosedAt(LocalDateTime.now());
            requests.saveAndFlush(live);
        });

        HrApprovalFlow flow = flowFor(employee.getPidBranch(), workflowCode, value);
        if (flow == null) return Optional.empty();

        HrApprovalRequest r = HrApprovalRequest.builder()
                .pidOrg(employee.getPidOrg()).pidCompany(employee.getPidCompany())
                .pidBranch(employee.getPidBranch())
                .workflowCode(workflowCode)
                .refId(refId)
                .pidEmployee(employee.getPidEmployee())
                .pidFlow(flow.getPidFlow())
                .flowName(flow.getFlowName())
                .routedValue(value)
                .currentLevel(1)
                .totalLevels(flow.getLevels().size())
                .requestStatus(HrApprovalRequest.PENDING)
                .startedBy(scope.actor())
                .startedAt(LocalDateTime.now())
                .build();
        r.setStatus("Y");
        requests.save(r);

        log.info("Approval chain started: {} {} routed on {} to '{}', {} levels",
                 workflowCode, refId, value, flow.getFlowName(), r.getTotalLevels());
        return Optional.of(r);
    }

    /**
     * The flow a value routes to.
     *
     * <p>The highest floor at or below the value, and failing that the flow with
     * no floor at all. A flow with no levels signs nothing and is skipped —
     * treating it as a chain would leave the object waiting for a signature
     * nobody can give.
     */
    private HrApprovalFlow flowFor(Long branchId, String workflowCode, BigDecimal value) {
        BigDecimal routed = value == null ? BigDecimal.ZERO : value;
        for (HrApprovalFlow f : flows.findMatching(branchId, workflowCode, routed)) {
            if (f.isUsable()) return f;
        }
        return null;
    }

    /* ══ Signing ════════════════════════════════════════════════════════ */

    /**
     * Records this actor's decision at the current level.
     *
     * <p>Returns {@link Step#NO_FLOW} when nothing is configured, which is the
     * answer that leaves every existing workflow untouched.
     */
    @Transactional
    public Step decide(String workflowCode, Long refId, boolean approve, String remarks) {
        Optional<HrApprovalRequest> found = requests.findLive(workflowCode, refId);
        if (found.isEmpty()) return Step.NO_FLOW;

        HrApprovalRequest r = found.get();
        HrApprovalFlowLevel level = levelOf(r);
        requireMaySign(r, level);

        HrApprovalAction action = HrApprovalAction.builder()
                .approval(r)
                .levelNo(r.getCurrentLevel())
                .levelLabel(level == null ? null : level.label())
                .approverRole(level == null ? null : level.getApproverRole())
                .decision(approve ? HrApprovalAction.APPROVED : HrApprovalAction.REJECTED)
                .decidedBy(scope.actor())
                .decidedAt(LocalDateTime.now())
                .remarks(remarks)
                .build();
        action.setStatus("Y");
        r.getActions().add(action);

        if (!approve) {
            /* One refusal ends the chain. The approvals already given stay on
               the trail — "approved by the manager, refused by the
               administrator" is a different story from "refused". */
            r.setRequestStatus(HrApprovalRequest.REJECTED);
            r.setClosedBy(scope.actor());
            r.setClosedAt(LocalDateTime.now());
            requests.save(r);
            log.info("Approval chain {} refused at level {} of {}",
                     r.getPidApproval(), r.getCurrentLevel(), r.getTotalLevels());
            return Step.REJECTED;
        }

        if (r.isFinalLevel()) {
            r.setRequestStatus(HrApprovalRequest.APPROVED);
            r.setCurrentLevel(r.getTotalLevels());
            r.setClosedBy(scope.actor());
            r.setClosedAt(LocalDateTime.now());
            requests.save(r);
            log.info("Approval chain {} complete at level {}",
                     r.getPidApproval(), r.getTotalLevels());
            return Step.FINAL;
        }

        r.setCurrentLevel(r.getCurrentLevel() + 1);
        requests.save(r);
        log.info("Approval chain {} moves to level {} of {}",
                 r.getPidApproval(), r.getCurrentLevel(), r.getTotalLevels());
        return Step.PENDING_NEXT;
    }

    /** Closes a chain because the object it belonged to was withdrawn. */
    @Transactional
    public void cancelFor(String workflowCode, Long refId) {
        requests.findLive(workflowCode, refId).ifPresent(r -> {
            r.setRequestStatus(HrApprovalRequest.CANCELLED);
            r.setClosedBy(scope.actor());
            r.setClosedAt(LocalDateTime.now());
            requests.save(r);
        });
    }

    /* ══ Who may sign ═══════════════════════════════════════════════════ */

    private HrApprovalFlowLevel levelOf(HrApprovalRequest r) {
        if (r.getPidFlow() == null) return null;
        return flows.findById(r.getPidFlow())
                .map(HrApprovalFlow::getLevels).orElse(List.of())
                .stream()
                .filter(l -> l.getLevelNo() != null && l.getLevelNo().equals(r.getCurrentLevel()))
                .findFirst().orElse(null);
    }

    /**
     * Refuses unless this actor is who the level asks for.
     *
     * <p>The module's Approve right was already checked by the caller. This is
     * the narrowing on top of it, and the message names who the request is
     * actually waiting on, because "forbidden" tells somebody nothing they can
     * act on.
     */
    private void requireMaySign(HrApprovalRequest r, HrApprovalFlowLevel level) {
        if (level == null) return;   // a chain whose flow lost its levels: do not trap the object

        if (level.isManagerLevel()) {
            HrEmployee subject = employees.findById(r.getPidEmployee()).orElse(null);
            Long manager = subject == null ? null : subject.getPidManager();
            if (manager == null) {
                /* Not skipped. A level nobody can sign is a configuration fault,
                   and quietly dropping a signature is exactly what a chain
                   exists to prevent. */
                throw new BusinessException(
                        (subject == null ? "This employee" : subject.getEmpName())
                        + " has no manager on record, and " + r.progress()
                        + " is theirs to sign. Set a manager on the employee, or change the flow.",
                        HttpStatus.CONFLICT, "HR_APPROVAL_NO_MANAGER");
            }
            Long actor = SecurityUtils.getCurrentEmployeeId();
            if (!manager.equals(actor)) {
                String name = employees.findById(manager).map(HrEmployee::getEmpName).orElse("their manager");
                throw new BusinessException(
                        r.progress() + " is " + name + "'s to sign.",
                        HttpStatus.FORBIDDEN, "HR_APPROVAL_WRONG_LEVEL");
            }
            return;
        }

        String actorRole = SecurityUtils.getCurrentRoleCode();
        if (actorRole == null || !actorRole.equalsIgnoreCase(level.getApproverRole())) {
            throw new BusinessException(
                    r.progress() + " is for " + level.label() + " to sign.",
                    HttpStatus.FORBIDDEN, "HR_APPROVAL_WRONG_LEVEL");
        }
    }

    /**
     * Whether this actor could sign the current level of a chain.
     *
     * <p>Used to build somebody's inbox, so it answers rather than throws.
     */
    public boolean canSign(HrApprovalRequest r) {
        try {
            requireMaySign(r, levelOf(r));
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /* ══ Reads ══════════════════════════════════════════════════════════ */

    /** The live chain for one object, for a screen that wants to show progress. */
    @Transactional(readOnly = true)
    public Optional<HrApprovalRequest> liveFor(String workflowCode, Long refId) {
        return requests.findLive(workflowCode, refId);
    }

    /** Everything still waiting at this branch. */
    @Transactional(readOnly = true)
    public List<HrApprovalRequest> pendingAt(Long branchId) {
        return requests.findByPidBranchAndRequestStatusAndStatusOrderByStartedAt(
                branchId, HrApprovalRequest.PENDING, "Y");
    }
}
