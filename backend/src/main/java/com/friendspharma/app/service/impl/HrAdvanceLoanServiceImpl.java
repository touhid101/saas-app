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

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Money lent to staff, and the plan by which payroll takes it back.
 *
 * <h3>Two things, not one</h3>
 *
 * <p>An advance is salary paid early: recovered from the next payslip or the
 * next two, with no schedule worth maintaining. A loan is a sum lent against a
 * plan, with a due date per installment and a record of which payroll took
 * which one. They share a screen and nothing else.
 *
 * <p><b>The schedule is written at approval, not as payroll runs.</b> An
 * employee should be shown the whole plan before they accept it; a plan that
 * does not exist until it is half over is not a plan.
 *
 * <p><b>HR does not write journals.</b> Disbursing money belongs in the general
 * ledger, and nothing here records that. The row carries the operational fact —
 * paid on this day, by this route, with this reference — and Accounts raises
 * the journal from its own financial event. That is what lets HR ship and be
 * used before Accounts exists.
 *
 * <p><b>Interest is recorded and never applied.</b> See {@link HrLoan}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrAdvanceLoanServiceImpl {

    private final HrAdvanceRepository      advanceRepo;
    private final HrLoanRepository         loanRepo;
    private final HrLoanScheduleRepository scheduleRepo;
    private final HrEmployeeRepository     employeeRepo;
    private final HrApprovalService        approvals;
    private final HrScope                  scope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;
    private final DocumentNumberService    documentNumbers;

    /** Number prefixes: AD2609 00001 and LN2609 00001, in the series every document shares. */
    static final String ADVANCE_PREFIX = "AD", LOAN_PREFIX = "LN";

    /* ══════════════════════════════════════════════════════════════════════
       Advance
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.AdvanceDto> searchAdvances(Long employeeId, String status,
                                                                Pageable pageable) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.VIEW, "see advances");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                advanceRepo.search(scope.branchId(), employeeId,
                                   status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(a -> map(a, staff.get(a.getPidEmployee()))));
    }

    @Transactional
    public HrResponses.AdvanceDto createAdvance(HrRequests.Advance req) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.CREATE, "request an advance");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        HrAdvance a = HrAdvance.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .requestDate(req.getRequestDate() == null ? LocalDate.now() : req.getRequestDate())
                .amount(req.getAmount())
                .installments(req.getInstallments() == null ? 1 : req.getInstallments())
                .recoverFrom(req.getRecoverFrom())
                .reason(req.getReason())
                .advanceStatus(HrConstants.AdvanceStatus.DRAFT)
                .build();
        a.setStatus("Y");
        advanceRepo.save(a);

        if (Boolean.TRUE.equals(req.getSubmit())) {
            a.setAdvanceStatus(HrConstants.AdvanceStatus.SUBMITTED);
            if (a.getAdvanceNo() == null) a.setAdvanceNo(documentNumbers.nextInvoiceNo(ADVANCE_PREFIX));
            advanceRepo.save(a);
        }
        return map(a, e);
    }

    @Transactional
    public HrResponses.AdvanceDto submitAdvance(Long id) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.CREATE, "submit an advance request");
        HrAdvance a = advance(id);
        requireStatus(a.getAdvanceStatus(), HrConstants.AdvanceStatus.DRAFT, "advance");
        a.setAdvanceStatus(HrConstants.AdvanceStatus.SUBMITTED);
        if (a.getAdvanceNo() == null) a.setAdvanceNo(documentNumbers.nextInvoiceNo(ADVANCE_PREFIX));
        advanceRepo.saveAndFlush(a);

        /* Routed on the amount asked for. */
        HrEmployee who = scope.employee(a.getPidEmployee());
        approvals.begin(HrScope.WF_ADVANCE, a.getPidAdvance(), who, a.getAmount());
        return map(a, who);
    }

    /**
     * Approves an advance, at the amount asked for or at less.
     *
     * <p>The approved amount is a separate column from the requested one
     * because an approver who cuts the figure has made a decision worth being
     * able to see afterwards — and every recovery is computed from what was
     * approved, never from what was asked.
     */
    @Transactional
    public HrResponses.AdvanceDto approveAdvance(Long id, HrRequests.AdvanceDecision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.APPROVE, "approve an advance");

        HrAdvance a = advance(id);
        requireStatus(a.getAdvanceStatus(), HrConstants.AdvanceStatus.SUBMITTED, "advance");

        /* Not necessarily the last signature. The approved amount is what every
           recovery is computed from, so it is not written until the chain has
           finished. */
        if (approvals.decide(HrScope.WF_ADVANCE, id, true,
                             decision == null ? null : decision.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(a, scope.employee(a.getPidEmployee()));
        }

        BigDecimal approved = decision != null && decision.getApprovedAmount() != null
                ? decision.getApprovedAmount() : a.getAmount();
        if (approved.compareTo(a.getAmount()) > 0) {
            throw new BusinessException(
                    "The approved amount is more than was asked for. Ask for a new request instead.",
                    HttpStatus.BAD_REQUEST, "HR_ADVANCE_OVER_REQUEST");
        }
        a.setApprovedAmount(approved);
        a.setAdvanceStatus(HrConstants.AdvanceStatus.APPROVED);
        a.setDecidedBy(scope.actor());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        return map(advanceRepo.save(a), scope.employee(a.getPidEmployee()));
    }

    @Transactional
    public HrResponses.AdvanceDto rejectAdvance(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.APPROVE, "reject an advance");
        HrAdvance a = advance(id);
        requireStatus(a.getAdvanceStatus(), HrConstants.AdvanceStatus.SUBMITTED, "advance");
        approvals.decide(HrScope.WF_ADVANCE, id, false,
                         decision == null ? null : decision.getRemarks());
        a.setAdvanceStatus(HrConstants.AdvanceStatus.REJECTED);
        a.setDecidedBy(scope.actor());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        return map(advanceRepo.save(a), scope.employee(a.getPidEmployee()));
    }

    /**
     * Records that the money has gone out.
     *
     * <p>This is the point recovery starts from: an approved advance is a
     * promise, and a paid one is a debt. Payroll reads {@code PAID} and
     * {@code RECOVERING} and nothing else.
     */
    @Transactional
    public HrResponses.AdvanceDto payAdvance(Long id, HrRequests.Disbursement req) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.EDIT, "record an advance payment");

        HrAdvance a = advance(id);
        requireStatus(a.getAdvanceStatus(), HrConstants.AdvanceStatus.APPROVED, "advance");
        a.setPaidDate(req.getPaidDate() == null ? LocalDate.now() : req.getPaidDate());
        a.setPaymentMode(requireMode(req.getPaymentMode()));
        a.setPaymentReference(req.getReference());
        a.setAdvanceStatus(HrConstants.AdvanceStatus.PAID);
        HrAdvance paidAdvance = advanceRepo.save(a);
        accounting.advancePaid(paidAdvance);
        return map(paidAdvance, scope.employee(a.getPidEmployee()));
    }

    @Transactional
    public HrResponses.AdvanceDto cancelAdvance(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.DELETE, "cancel an advance");

        HrAdvance a = advance(id);
        if (nz(a.getRecoveredAmount()).signum() > 0) {
            throw new BusinessException(
                    "Part of this advance has already been recovered from a payslip. "
                    + "It cannot be cancelled — write off the balance instead.",
                    HttpStatus.CONFLICT, "HR_ADVANCE_PARTLY_RECOVERED");
        }
        a.setAdvanceStatus(HrConstants.AdvanceStatus.CANCELLED);
        a.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        return map(advanceRepo.save(a), scope.employee(a.getPidEmployee()));
    }

    /* ══════════════════════════════════════════════════════════════════════
       Loan
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.LoanDto> searchLoans(Long employeeId, String status, Pageable pageable) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.VIEW, "see loans");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                loanRepo.search(scope.branchId(), employeeId,
                                status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(l -> map(l, staff.get(l.getPidEmployee()), false)));
    }

    @Transactional(readOnly = true)
    public HrResponses.LoanDto getLoan(Long id) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.VIEW, "see loans");
        HrLoan l = loan(id);
        return map(l, scope.employee(l.getPidEmployee()), true);
    }

    @Transactional
    public HrResponses.LoanDto createLoan(HrRequests.Loan req) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.CREATE, "request a loan");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        HrLoan l = HrLoan.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .requestDate(req.getRequestDate() == null ? LocalDate.now() : req.getRequestDate())
                .principal(req.getPrincipal())
                .installments(req.getInstallments())
                .interestRate(req.getInterestRate())
                .startDate(req.getStartDate())
                .purpose(req.getPurpose())
                .loanStatus(HrConstants.LoanStatus.DRAFT)
                .build();
        l.setStatus("Y");
        loanRepo.save(l);

        if (Boolean.TRUE.equals(req.getSubmit())) {
            l.setLoanStatus(HrConstants.LoanStatus.SUBMITTED);
            if (l.getLoanNo() == null) l.setLoanNo(documentNumbers.nextInvoiceNo(LOAN_PREFIX));
            loanRepo.save(l);
        }
        return map(l, e, false);
    }

    @Transactional
    public HrResponses.LoanDto submitLoan(Long id) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.CREATE, "submit a loan request");
        HrLoan l = loan(id);
        requireStatus(l.getLoanStatus(), HrConstants.LoanStatus.DRAFT, "loan");
        l.setLoanStatus(HrConstants.LoanStatus.SUBMITTED);
        if (l.getLoanNo() == null) l.setLoanNo(documentNumbers.nextInvoiceNo(LOAN_PREFIX));
        loanRepo.saveAndFlush(l);

        /* Routed on the principal. A loan has its own chain rather than sharing
           the advance's: they are the same module but not the same decision, and
           a pharmacy may well want more signatures on the longer arrangement. */
        HrEmployee borrower = scope.employee(l.getPidEmployee());
        approvals.begin(HrScope.WF_LOAN, l.getPidLoan(), borrower, l.getPrincipal());
        return map(l, borrower, false);
    }

    /**
     * Approves a loan and writes its whole repayment schedule.
     *
     * <p>Installments are equal to two decimal places and the last one carries
     * whatever rounding left behind, so the schedule adds up to the principal
     * exactly. Splitting 10,000 over three months as 3,333.33 three times
     * returns 9,999.99 and leaves a penny outstanding for ever.
     */
    @Transactional
    public HrResponses.LoanDto approveLoan(Long id, HrRequests.LoanDecision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.APPROVE, "approve a loan");

        HrLoan l = loan(id);
        requireStatus(l.getLoanStatus(), HrConstants.LoanStatus.SUBMITTED, "loan");

        /* Not necessarily the last signature. The repayment schedule is written
           by this method, and writing one for a loan nobody has finished
           approving would put installments in front of payroll. */
        if (approvals.decide(HrScope.WF_LOAN, id, true,
                             decision == null ? null : decision.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(l, scope.employee(l.getPidEmployee()), false);
        }

        BigDecimal approved = decision != null && decision.getApprovedPrincipal() != null
                ? decision.getApprovedPrincipal() : l.getPrincipal();
        if (approved.compareTo(l.getPrincipal()) > 0) {
            throw new BusinessException(
                    "The approved principal is more than was asked for. Ask for a new request instead.",
                    HttpStatus.BAD_REQUEST, "HR_LOAN_OVER_REQUEST");
        }
        int installments = decision != null && decision.getInstallments() != null
                ? decision.getInstallments() : l.getInstallments();
        if (installments < 1) {
            throw new BusinessException("A loan is repaid over at least one installment.",
                    HttpStatus.BAD_REQUEST, "HR_LOAN_INSTALLMENTS");
        }
        LocalDate start = decision != null && decision.getStartDate() != null
                ? decision.getStartDate()
                : (l.getStartDate() != null ? l.getStartDate() : LocalDate.now().plusMonths(1));

        l.setApprovedPrincipal(approved);
        l.setInstallments(installments);
        l.setStartDate(start);
        l.setLoanStatus(HrConstants.LoanStatus.APPROVED);
        l.setDecidedBy(scope.actor());
        l.setDecidedAt(LocalDateTime.now());
        l.setDecisionRemarks(decision == null ? null : decision.getRemarks());

        buildSchedule(l, approved, installments, start);
        loanRepo.save(l);
        return map(l, scope.employee(l.getPidEmployee()), true);
    }

    /** Equal installments, with the remainder on the last one. */
    private void buildSchedule(HrLoan l, BigDecimal principal, int installments, LocalDate start) {
        l.getSchedule().clear();

        BigDecimal per = principal.divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal allocated = BigDecimal.ZERO;

        for (int i = 1; i <= installments; i++) {
            BigDecimal due = (i == installments) ? principal.subtract(allocated) : per;
            allocated = allocated.add(due);

            HrLoanSchedule s = HrLoanSchedule.builder()
                    .loan(l)
                    .installmentNo(i)
                    .dueDate(start.plusMonths(i - 1L))
                    .dueAmount(due)
                    .recoveredAmount(BigDecimal.ZERO)
                    .scheduleStatus(HrConstants.ScheduleStatus.PENDING)
                    .build();
            s.setStatus("Y");
            l.getSchedule().add(s);
        }
        l.setInstallmentAmount(per);
    }

    @Transactional
    public HrResponses.LoanDto rejectLoan(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.APPROVE, "reject a loan");
        HrLoan l = loan(id);
        requireStatus(l.getLoanStatus(), HrConstants.LoanStatus.SUBMITTED, "loan");
        approvals.decide(HrScope.WF_LOAN, id, false,
                         decision == null ? null : decision.getRemarks());
        l.setLoanStatus(HrConstants.LoanStatus.REJECTED);
        l.setDecidedBy(scope.actor());
        l.setDecidedAt(LocalDateTime.now());
        l.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        return map(loanRepo.save(l), scope.employee(l.getPidEmployee()), false);
    }

    @Transactional
    public HrResponses.LoanDto disburseLoan(Long id, HrRequests.Disbursement req) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.EDIT, "record a loan disbursement");

        HrLoan l = loan(id);
        requireStatus(l.getLoanStatus(), HrConstants.LoanStatus.APPROVED, "loan");
        l.setDisbursedDate(req.getPaidDate() == null ? LocalDate.now() : req.getPaidDate());
        l.setPaymentMode(requireMode(req.getPaymentMode()));
        l.setPaymentReference(req.getReference());
        l.setLoanStatus(HrConstants.LoanStatus.DISBURSED);
        HrLoan disbursed = loanRepo.save(l);
        accounting.loanDisbursed(disbursed);
        return map(disbursed, scope.employee(l.getPidEmployee()), true);
    }

    @Transactional
    public HrResponses.LoanDto cancelLoan(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_ADVANCE, PermissionGuard.Right.DELETE, "cancel a loan");

        HrLoan l = loan(id);
        if (nz(l.getRecoveredAmount()).signum() > 0) {
            throw new BusinessException(
                    "Part of this loan has already been recovered from a payslip. "
                    + "It cannot be cancelled — write off the balance instead.",
                    HttpStatus.CONFLICT, "HR_LOAN_PARTLY_RECOVERED");
        }
        l.setLoanStatus(HrConstants.LoanStatus.CANCELLED);
        l.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        for (HrLoanSchedule s : l.getSchedule()) {
            s.setScheduleStatus(HrConstants.ScheduleStatus.CANCELLED);
        }
        return map(loanRepo.save(l), scope.employee(l.getPidEmployee()), true);
    }

    /* ══ Lookups, guards, mapping ═══════════════════════════════════════ */

    HrAdvance advance(Long id) {
        return advanceRepo.findByPidAdvanceAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Advance", id));
    }

    HrLoan loan(Long id) {
        return loanRepo.findByPidLoanAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan", id));
    }

    private static void requireStatus(String actual, String wanted, String what) {
        if (!wanted.equals(actual)) {
            throw new BusinessException(
                    "This " + what + " is " + HrConstants.label(actual).toLowerCase()
                    + ", and that step needs it to be " + HrConstants.label(wanted).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_WRONG_STATE");
        }
    }

    private static String requireMode(String raw) {
        String mode = HrConstants.normalise(HrConstants.PaymentMode.ALL, raw);
        if (mode == null) {
            throw new BusinessException(
                    "'" + raw + "' is not a payment method. Use one of: "
                    + String.join(", ", HrConstants.PaymentMode.ALL) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        return mode;
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.AdvanceDto map(HrAdvance a, HrEmployee e) {
        return HrResponses.AdvanceDto.builder()
                .id(a.getPidAdvance())
                .advanceNo(a.getAdvanceNo())
                .employeeId(a.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .requestDate(a.getRequestDate())
                .amount(a.getAmount())
                .approvedAmount(a.getApprovedAmount())
                .recoveredAmount(a.getRecoveredAmount())
                .outstanding(a.getOutstanding())
                .installmentAmount(a.getInstallmentAmount())
                .installments(a.getInstallments())
                .recoverFrom(a.getRecoverFrom())
                .reason(a.getReason())
                .advanceStatus(a.getAdvanceStatus())
                .advanceStatusLabel(HrConstants.label(a.getAdvanceStatus()))
                .decidedBy(a.getDecidedBy())
                .decidedAt(a.getDecidedAt())
                .decisionRemarks(a.getDecisionRemarks())
                .paidDate(a.getPaidDate())
                .paymentMode(a.getPaymentMode())
                .paymentReference(a.getPaymentReference())
                .build();
    }

    HrResponses.LoanDto map(HrLoan l, HrEmployee e, boolean withSchedule) {
        List<HrResponses.LoanScheduleDto> schedule = null;
        if (withSchedule) {
            schedule = l.getSchedule().stream()
                    .map(s -> HrResponses.LoanScheduleDto.builder()
                            .id(s.getPidSchedule())
                            .installmentNo(s.getInstallmentNo())
                            .dueDate(s.getDueDate())
                            .dueAmount(s.getDueAmount())
                            .recoveredAmount(s.getRecoveredAmount())
                            .remaining(s.getRemaining())
                            .scheduleStatus(s.getScheduleStatus())
                            .scheduleStatusLabel(HrConstants.label(s.getScheduleStatus()))
                            .payrollId(s.getPidPayroll())
                            .recoveredOn(s.getRecoveredOn())
                            .build())
                    .toList();
        }
        return HrResponses.LoanDto.builder()
                .id(l.getPidLoan())
                .loanNo(l.getLoanNo())
                .employeeId(l.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .requestDate(l.getRequestDate())
                .principal(l.getPrincipal())
                .approvedPrincipal(l.getApprovedPrincipal())
                .installmentAmount(l.getInstallmentAmount())
                .installments(l.getInstallments())
                .recoveredAmount(l.getRecoveredAmount())
                .outstanding(l.getOutstanding())
                .interestRate(l.getInterestRate())
                .startDate(l.getStartDate())
                .purpose(l.getPurpose())
                .loanStatus(l.getLoanStatus())
                .loanStatusLabel(HrConstants.label(l.getLoanStatus()))
                .decidedBy(l.getDecidedBy())
                .decidedAt(l.getDecidedAt())
                .decisionRemarks(l.getDecisionRemarks())
                .disbursedDate(l.getDisbursedDate())
                .paymentMode(l.getPaymentMode())
                .paymentReference(l.getPaymentReference())
                .schedule(schedule)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
