package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Salary increments.
 *
 * <h3>The figure that must not move</h3>
 *
 * <p>The three money columns are frozen when the increment is <b>approved</b>,
 * and read back unchanged when it is applied. A 10% increment signed off in
 * March against 20,000 is 2,000. If the basic salary is edited to 25,000 in
 * April, applying that increment must still be the 2,000 somebody agreed to —
 * recomputing at apply time would quietly change a decision after it was made.
 *
 * <p>And when the salary has moved underneath an approved increment, applying
 * is <b>refused</b> rather than guessed at. Writing the frozen NEW_SALARY over
 * a figure that changed for some other reason would discard that change
 * silently; refusing puts the question back to a person who can see both.
 *
 * <h3>Why this is also the salary history</h3>
 *
 * <p>{@code HR_EMPLOYEES.BASIC_SALARY} holds only the current figure. These
 * rows are the record of how it got there, which is why an applied increment is
 * never deleted — only reversed by a further one.
 *
 * <h3>What it does not touch</h3>
 *
 * <p>Applying an increment changes what the employee is paid <em>from now on</em>.
 * It does not reach into a payroll run that has already been calculated, and
 * certainly not a POSTED one: a payslip is what was decided at the time it was
 * decided. An increment dated inside an open period takes effect the next time
 * that run is calculated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrIncrementServiceImpl {

    private final HrIncrementRepository  incrementRepo;
    private final HrEmployeeRepository   employeeRepo;
    private final HrDesignationRepository designationRepo;
    private final HrApprovalService      approvals;
    private final HrScope                scope;

    /* ══ Reads ══════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.IncrementDto> search(Long employeeId, String status,
                                                          Pageable pageable) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see increments");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                incrementRepo.search(scope.branchId(), employeeId,
                                     status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(i -> map(i, staff.get(i.getPidEmployee()))));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.IncrementDto> pending() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see increments");
        Map<Long, HrEmployee> staff = staffById();
        return incrementRepo.findByPidBranchAndIncrementStatusAndStatusOrderByEffectiveDate(
                        scope.branchId(), HrIncrement.SUBMITTED, "Y")
                .stream().map(i -> map(i, staff.get(i.getPidEmployee()))).toList();
    }

    /** Approved, dated today or earlier, and not yet applied — what the screen offers to apply. */
    @Transactional(readOnly = true)
    public List<HrResponses.IncrementDto> due() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see increments");
        Map<Long, HrEmployee> staff = staffById();
        return incrementRepo.findDue(scope.branchId(), LocalDate.now())
                .stream().map(i -> map(i, staff.get(i.getPidEmployee()))).toList();
    }

    /** One person's salary history, newest first. */
    @Transactional(readOnly = true)
    public List<HrResponses.IncrementDto> history(Long employeeId) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see increments");
        HrEmployee e = scope.employee(employeeId);
        return incrementRepo.findByPidEmployeeAndStatusOrderByEffectiveDateDesc(e.getPidEmployee(), "Y")
                .stream().map(i -> map(i, e)).toList();
    }

    /* ══ The lifecycle ══════════════════════════════════════════════════ */

    @Transactional
    public HrResponses.IncrementDto create(HrRequests.Increment req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.CREATE, "propose an increment");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        String basis = normaliseBasis(req.getIncrementBasis());
        String type  = normaliseType(req.getIncrementType());
        requireValue(basis, req.getPctValue(), req.getFixedAmount());

        if (!incrementRepo.findLiveFor(e.getPidEmployee(), req.getEffectiveDate(), null).isEmpty()) {
            throw new BusinessException(
                    e.getEmpName() + " already has an increment awaiting a decision for "
                    + req.getEffectiveDate() + ".",
                    HttpStatus.CONFLICT, "HR_INCREMENT_ALREADY_PROPOSED");
        }

        Long newDesignation = req.getNewDesignationId();
        if (newDesignation != null && !designationRepo.existsById(newDesignation)) {
            throw new ResourceNotFoundException("Designation", newDesignation);
        }

        HrIncrement i = HrIncrement.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .incrementType(type)
                .incrementBasis(basis)
                .pctValue(req.getPctValue())
                .fixedAmount(req.getFixedAmount())
                .effectiveDate(req.getEffectiveDate())
                .pidNewDesignation(newDesignation)
                .newGrade(trim(req.getNewGrade()))
                .reason(req.getReason())
                .incrementStatus(HrIncrement.DRAFT)
                .build();

        /* Shown while it is still a draft so the proposer can see what they are
           proposing. These are indicative until approval freezes them. */
        BigDecimal current = basic(e);
        i.setPreviousSalary(current);
        i.setIncrementAmount(HrIncrement.amountFor(basis, req.getPctValue(), req.getFixedAmount(), current));
        i.setNewSalary(current.add(i.getIncrementAmount()));

        i.setStatus("Y");
        incrementRepo.save(i);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(i);
        return map(i, e);
    }

    @Transactional
    public HrResponses.IncrementDto update(Long id, HrRequests.Increment req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT, "edit an increment");

        HrIncrement i = increment(id);
        if (!i.isDraft()) {
            throw new BusinessException(
                    "Only a draft can be edited. This one is "
                    + HrConstants.label(i.getIncrementStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_INCREMENT_NOT_DRAFT");
        }
        HrEmployee e = scope.employee(i.getPidEmployee());
        String basis = normaliseBasis(req.getIncrementBasis());
        requireValue(basis, req.getPctValue(), req.getFixedAmount());

        if (!incrementRepo.findLiveFor(e.getPidEmployee(), req.getEffectiveDate(), id).isEmpty()) {
            throw new BusinessException(
                    e.getEmpName() + " already has an increment awaiting a decision for "
                    + req.getEffectiveDate() + ".",
                    HttpStatus.CONFLICT, "HR_INCREMENT_ALREADY_PROPOSED");
        }

        i.setIncrementType(normaliseType(req.getIncrementType()));
        i.setIncrementBasis(basis);
        i.setPctValue(req.getPctValue());
        i.setFixedAmount(req.getFixedAmount());
        i.setEffectiveDate(req.getEffectiveDate());
        i.setPidNewDesignation(req.getNewDesignationId());
        i.setNewGrade(trim(req.getNewGrade()));
        i.setReason(req.getReason());

        BigDecimal current = basic(e);
        i.setPreviousSalary(current);
        i.setIncrementAmount(HrIncrement.amountFor(basis, req.getPctValue(), req.getFixedAmount(), current));
        i.setNewSalary(current.add(i.getIncrementAmount()));

        incrementRepo.save(i);
        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(i);
        return map(i, e);
    }

    @Transactional
    public HrResponses.IncrementDto submit(Long id) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.CREATE, "submit an increment");
        HrIncrement i = increment(id);
        submitInternal(i);
        return map(i, scope.employee(i.getPidEmployee()));
    }

    private void submitInternal(HrIncrement i) {
        if (!i.isDraft()) {
            throw new BusinessException("This increment has already been submitted.",
                    HttpStatus.CONFLICT, "HR_INCREMENT_ALREADY_SUBMITTED");
        }
        i.setIncrementStatus(HrIncrement.SUBMITTED);
        i.setSubmittedBy(scope.actor());
        i.setSubmittedAt(LocalDateTime.now());
        incrementRepo.saveAndFlush(i);

        /* Routed on what the rise is worth. The figure is indicative until
           approval freezes it, which is the right basis for deciding how many
           signatures it needs. */
        approvals.begin(HrScope.WF_INCREMENT, i.getPidIncrement(),
                        scope.employee(i.getPidEmployee()), i.getIncrementAmount());
    }

    /**
     * Approves, and freezes the three money figures.
     *
     * <p>Everything after this reads them; nothing recomputes them.
     */
    @Transactional
    public HrResponses.IncrementDto approve(Long id, HrRequests.IncrementDecision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "approve an increment");

        HrIncrement i = increment(id);
        requireSubmitted(i);
        HrEmployee e = scope.employee(i.getPidEmployee());

        /* Not necessarily the last signature. Nothing is frozen until it is —
           freezing at an intermediate level would fix a figure against a salary
           that may still move before the chain finishes. */
        if (approvals.decide(HrScope.WF_INCREMENT, id, true, d == null ? null : d.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(i, e);
        }

        BigDecimal current = basic(e);
        BigDecimal amount  = d != null && d.getIncrementAmount() != null
                ? d.getIncrementAmount().setScale(2, RoundingMode.HALF_UP)
                : HrIncrement.amountFor(i.getIncrementBasis(), i.getPctValue(),
                                        i.getFixedAmount(), current);

        if (amount.signum() <= 0) {
            throw new BusinessException(
                    "An increment must be worth more than zero. Reject it instead.",
                    HttpStatus.BAD_REQUEST, "HR_INCREMENT_ZERO");
        }

        i.setPreviousSalary(current);
        i.setIncrementAmount(amount);
        i.setNewSalary(current.add(amount));
        i.setIncrementStatus(HrIncrement.APPROVED);
        i.setDecidedBy(scope.actor());
        i.setDecidedAt(LocalDateTime.now());
        i.setDecisionRemarks(d == null ? null : d.getRemarks());
        incrementRepo.save(i);

        log.info("Increment {} approved for employee {}: {} to {} from {}",
                 i.getPidIncrement(), e.getPidEmployee(), current, i.getNewSalary(),
                 i.getEffectiveDate());
        return map(i, e);
    }

    @Transactional
    public HrResponses.IncrementDto reject(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "reject an increment");
        HrIncrement i = increment(id);
        requireSubmitted(i);
        approvals.decide(HrScope.WF_INCREMENT, id, false, d == null ? null : d.getRemarks());
        i.setIncrementStatus(HrIncrement.REJECTED);
        i.setDecidedBy(scope.actor());
        i.setDecidedAt(LocalDateTime.now());
        i.setDecisionRemarks(d == null ? null : d.getRemarks());
        return map(incrementRepo.save(i), scope.employee(i.getPidEmployee()));
    }

    /**
     * Applies an approved increment to the employee's basic salary.
     *
     * <p>Refused before the effective date — an increment effective in October
     * is not October's salary in September — and refused when the salary has
     * moved since approval, for the reason in the class comment.
     */
    @Transactional
    public HrResponses.IncrementDto apply(Long id) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "apply an increment");

        HrIncrement i = increment(id);
        if (!i.isApproved()) {
            throw new BusinessException(
                    "Only an approved increment can be applied. This one is "
                    + HrConstants.label(i.getIncrementStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_INCREMENT_NOT_APPROVED");
        }
        if (i.getEffectiveDate().isAfter(LocalDate.now())) {
            throw new BusinessException(
                    "This increment takes effect on " + i.getEffectiveDate()
                    + ". It can be applied on or after that date.",
                    HttpStatus.CONFLICT, "HR_INCREMENT_NOT_DUE");
        }

        HrEmployee e = scope.activeEmployee(i.getPidEmployee());
        BigDecimal current = basic(e);
        if (current.compareTo(i.getPreviousSalary() == null ? BigDecimal.ZERO : i.getPreviousSalary()) != 0) {
            throw new BusinessException(
                    e.getEmpName() + " is now on " + current + ", not the "
                    + i.getPreviousSalary() + " this increment was approved against. "
                    + "The salary changed after the decision — cancel this increment and "
                    + "propose one against the figure that is actually in force.",
                    HttpStatus.CONFLICT, "HR_INCREMENT_SALARY_MOVED");
        }

        e.setBasicSalary(i.getNewSalary());
        if (i.getPidNewDesignation() != null) e.setPidDesignation(i.getPidNewDesignation());
        if (i.getNewGrade() != null && !i.getNewGrade().isBlank()) e.setGrade(i.getNewGrade());
        employeeRepo.save(e);

        i.setIncrementStatus(HrIncrement.APPLIED);
        i.setAppliedBy(scope.actor());
        i.setAppliedAt(LocalDateTime.now());
        incrementRepo.save(i);

        log.info("Increment {} applied: employee {} basic salary now {}",
                 i.getPidIncrement(), e.getPidEmployee(), e.getBasicSalary());
        return map(i, e);
    }

    /**
     * Cancels an increment that has not been applied.
     *
     * <p>An applied one is not cancellable: it is the salary history, and the
     * way to undo it is a further increment that says so.
     */
    @Transactional
    public HrResponses.IncrementDto cancel(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.DELETE, "cancel an increment");

        HrIncrement i = increment(id);
        if (i.isApplied()) {
            throw new BusinessException(
                    "This increment has been applied and is part of the salary history. "
                    + "Propose a further increment to change the salary back.",
                    HttpStatus.CONFLICT, "HR_INCREMENT_APPLIED");
        }
        if (HrIncrement.CANCELLED.equals(i.getIncrementStatus())
                || HrIncrement.REJECTED.equals(i.getIncrementStatus())) {
            throw new BusinessException("This increment is already closed.",
                    HttpStatus.CONFLICT, "HR_INCREMENT_ALREADY_CLOSED");
        }

        approvals.cancelFor(HrScope.WF_INCREMENT, id);
        i.setIncrementStatus(HrIncrement.CANCELLED);
        i.setDecidedBy(scope.actor());
        i.setDecidedAt(LocalDateTime.now());
        i.setDecisionRemarks(d == null ? null : d.getRemarks());
        return map(incrementRepo.save(i), scope.employee(i.getPidEmployee()));
    }

    /* ══ Guards ═════════════════════════════════════════════════════════ */

    private static String normaliseBasis(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        if (!HrIncrement.BASES.contains(v)) {
            throw new BusinessException(
                    "An increment is either a percentage or a fixed amount.",
                    HttpStatus.BAD_REQUEST, "HR_INCREMENT_BASIS_INVALID");
        }
        return v;
    }

    private static String normaliseType(String raw) {
        String v = raw == null || raw.isBlank() ? "ANNUAL" : raw.trim().toUpperCase();
        if (!HrIncrement.TYPES.contains(v)) {
            throw new BusinessException(
                    "Unknown increment type: " + raw + ". Expected one of " + HrIncrement.TYPES + ".",
                    HttpStatus.BAD_REQUEST, "HR_INCREMENT_TYPE_INVALID");
        }
        return v;
    }

    private static void requireValue(String basis, BigDecimal pct, BigDecimal fixed) {
        if (HrIncrement.PERCENT.equals(basis)) {
            if (pct == null || pct.signum() <= 0) {
                throw new BusinessException("What percentage?",
                        HttpStatus.BAD_REQUEST, "HR_INCREMENT_PCT_REQUIRED");
            }
        } else if (fixed == null || fixed.signum() <= 0) {
            throw new BusinessException("How much?",
                    HttpStatus.BAD_REQUEST, "HR_INCREMENT_AMOUNT_REQUIRED");
        }
    }

    private static void requireSubmitted(HrIncrement i) {
        if (!i.isSubmitted()) {
            throw new BusinessException(
                    "Only a submitted increment can be decided. This one is "
                    + HrConstants.label(i.getIncrementStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_INCREMENT_NOT_SUBMITTED");
        }
    }

    /**
     * What the employee is on.
     *
     * <p>Zero when nobody has set a basic salary yet, which is a legitimate
     * state for a record created before V73 added the column. A percentage of
     * nothing is nothing, and the FIXED basis still works — so an increment can
     * be the thing that first puts a salary on the record.
     */
    private static BigDecimal basic(HrEmployee e) {
        return e.getBasicSalary() == null
                ? BigDecimal.ZERO.setScale(2)
                : e.getBasicSalary().setScale(2, RoundingMode.HALF_UP);
    }

    private HrIncrement increment(Long id) {
        return incrementRepo.findByPidIncrementAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Increment", id));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.IncrementDto map(HrIncrement i, HrEmployee e) {
        return HrResponses.IncrementDto.builder()
                .id(i.getPidIncrement())
                .incrementNo(i.getIncrementNo())
                .employeeId(i.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .incrementType(i.getIncrementType())
                .incrementTypeLabel(HrConstants.label(i.getIncrementType()))
                .incrementBasis(i.getIncrementBasis())
                .pctValue(i.getPctValue())
                .fixedAmount(i.getFixedAmount())
                .previousSalary(i.getPreviousSalary())
                .incrementAmount(i.getIncrementAmount())
                .newSalary(i.getNewSalary())
                .effectiveDate(i.getEffectiveDate())
                .newDesignationId(i.getPidNewDesignation())
                .newGrade(i.getNewGrade())
                .reason(i.getReason())
                .incrementStatus(i.getIncrementStatus())
                .incrementStatusLabel(HrConstants.label(i.getIncrementStatus()))
                .submittedBy(i.getSubmittedBy())
                .submittedAt(i.getSubmittedAt())
                .decidedBy(i.getDecidedBy())
                .decidedAt(i.getDecidedAt())
                .decisionRemarks(i.getDecisionRemarks())
                .appliedBy(i.getAppliedBy())
                .appliedAt(i.getAppliedAt())
                .editable(i.isDraft())
                .applicable(i.isApproved() && !i.getEffectiveDate().isAfter(LocalDate.now()))
                .build();
    }
}
