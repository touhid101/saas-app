package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.util.PayrollCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The monthly run: calculate, review, approve, post, pay.
 *
 * <h3>The lifecycle, and where it stops</h3>
 *
 * <pre>
 *   DRAFT -> CALCULATED -> REVIEWED -> APPROVED -> POSTED -> PAID
 *                                                     |
 *                                CANCELLED  &lt;---------+  (only before POSTED)
 * </pre>
 *
 * <p>Up to APPROVED the run can be recalculated as often as anyone likes; that
 * is what the review step is for. <b>At POSTED it stops.</b> The run has been
 * handed to Accounts and a journal exists against it, so no update, no delete,
 * no recalculation — a correction is a reversal and a fresh run, which is the
 * rule the general ledger lives by.
 *
 * <h3>Recalculation has to be safe, and that is the hard part</h3>
 *
 * <p>A second calculation of the same run must produce the same answer as the
 * first, not a smaller one. Two things would otherwise break that:
 *
 * <ul>
 *   <li><b>Loan installments.</b> The first pass marks the schedule rows it
 *       took with this run's id. A naive second pass would find them taken and
 *       skip them, and the recalculated payslip would be short by exactly the
 *       recovery the first attempt made. So the run <em>releases</em> everything
 *       it previously claimed before it calculates again.</li>
 *   <li><b>Advance recovery.</b> Same shape, one level up: the amount already
 *       written back onto the advance is subtracted before the new figure is
 *       written.</li>
 * </ul>
 *
 * <h3>Everything is read once</h3>
 *
 * <p>Attendance, leave, structures, advances and loans are each pulled for the
 * whole branch in a single query before the loop. Reading them per employee
 * would be five queries per payslip — two hundred round trips on a shop of
 * forty, inside one transaction.
 *
 * <h3>What this does not do</h3>
 *
 * <p><b>It writes no journal.</b> {@link #post} freezes the run and stamps it.
 * The accounting entry is Accounts' business and attaches at that point — see
 * {@code 10-payroll-accounting.md}. That division is what lets HR ship and be
 * used before Accounts exists.
 *
 * <p><b>It computes no tax.</b> {@link PayrollCalculator#TAX_NOT_CONFIGURED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPayrollServiceImpl {

    private final HrPayrollPeriodRepository   periodRepo;
    private final HrPayrollRepository         payrollRepo;
    private final HrPayrollLineRepository     lineRepo;
    private final HrSalaryStructureRepository structureRepo;
    private final HrSalaryComponentRepository componentRepo;
    private final HrAttendanceRepository      attendanceRepo;
    private final HrLeaveRequestRepository    leaveRepo;
    private final HrLeaveTypeRepository       leaveTypeRepo;
    private final HrAdvanceRepository         advanceRepo;
    private final HrLoanRepository            loanRepo;
    private final HrLoanScheduleRepository    scheduleRepo;
    private final HrSalaryPaymentRepository   paymentRepo;
    private final HrEmployeeRepository        employeeRepo;
    private final DocumentNumberService       documentNumbers;
    private final HrPfServiceImpl             pf;
    private final HrScope                     scope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /** Payroll runs share the document-number series, with their own prefix. */
    private static final String PAYROLL_PREFIX = "PR";
    private static final String VOUCHER_PREFIX = "SP";

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /* ══════════════════════════════════════════════════════════════════════
       Periods
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.PayrollPeriodDto> listPeriods(Integer year) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see payroll periods");
        Long branch = scope.branchId();
        List<HrPayrollPeriod> periods = year == null
                ? periodRepo.findByPidBranchAndStatusOrderByPeriodYearDescPeriodMonthDesc(branch, "Y")
                : periodRepo.findByPidBranchAndPeriodYearAndStatusOrderByPeriodMonth(branch, year, "Y");
        return periods.stream().map(this::mapPeriod).toList();
    }

    /**
     * Opens a pay month.
     *
     * <p>Dates default to the calendar month. {@code workingDays} does not
     * default to anything, because payroll divides by it to value an absence
     * and a guessed figure changes what people are paid.
     */
    @Transactional
    public HrResponses.PayrollPeriodDto createPeriod(HrRequests.PayrollPeriod req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.CREATE, "open a payroll period");

        Long branch = scope.branchId();
        periodRepo.findByPidBranchAndPeriodYearAndPeriodMonthAndStatus(
                branch, req.getPeriodYear(), req.getPeriodMonth(), "Y").ifPresent(x -> {
            throw new BusinessException(
                    x.getLabel() + " has already been opened.",
                    HttpStatus.CONFLICT, "HR_PERIOD_EXISTS");
        });

        YearMonth ym = YearMonth.of(req.getPeriodYear(), req.getPeriodMonth());
        LocalDate from = req.getPeriodFrom() == null ? ym.atDay(1)        : req.getPeriodFrom();
        LocalDate to   = req.getPeriodTo()   == null ? ym.atEndOfMonth()  : req.getPeriodTo();
        if (to.isBefore(from)) {
            throw new BusinessException("The period ends before it begins.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }

        // Two periods containing the same day would let one day of attendance
        // be paid twice.
        List<HrPayrollPeriod> overlapping = periodRepo
                .findByPidBranchAndStatusAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
                        branch, "Y", to, from);
        if (!overlapping.isEmpty()) {
            HrPayrollPeriod clash = overlapping.get(0);
            throw new BusinessException(
                    "These dates overlap " + clash.getLabel() + " (" + clash.getPeriodFrom()
                    + " to " + clash.getPeriodTo() + "). One day cannot belong to two pay months.",
                    HttpStatus.CONFLICT, "HR_PERIOD_OVERLAP");
        }

        long calendarDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (req.getWorkingDays().compareTo(BigDecimal.valueOf(calendarDays)) > 0) {
            throw new BusinessException(
                    "There are only " + calendarDays + " days between those dates.",
                    HttpStatus.BAD_REQUEST, "HR_PERIOD_WORKING_DAYS");
        }

        HrPayrollPeriod p = HrPayrollPeriod.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(branch)
                .periodYear(req.getPeriodYear())
                .periodMonth(req.getPeriodMonth())
                .periodFrom(from)
                .periodTo(to)
                .workingDays(req.getWorkingDays())
                .periodStatus(HrPayrollPeriod.OPEN)
                .remarks(req.getRemarks())
                .build();
        p.setStatus("Y");
        return mapPeriod(periodRepo.save(p));
    }

    /**
     * Closes a period.
     *
     * <p>Refused while its run is unfinished: closing a month whose payroll has
     * not been posted would leave the run stranded, editable in principle and
     * unreachable in practice.
     */
    @Transactional
    public HrResponses.PayrollPeriodDto closePeriod(Long periodId) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT, "close a payroll period");

        HrPayrollPeriod p = period(periodId);
        payrollRepo.findLiveRun(p.getPidBranch(), periodId).ifPresent(run -> {
            if (!run.isFrozen()) {
                throw new BusinessException(
                        "The payroll for " + p.getLabel() + " is still "
                        + HrConstants.label(run.getPayrollStatus()).toLowerCase()
                        + ". Post it or cancel it before closing the period.",
                        HttpStatus.CONFLICT, "HR_PERIOD_RUN_OPEN");
            }
        });
        p.setPeriodStatus(HrPayrollPeriod.CLOSED);
        return mapPeriod(periodRepo.save(p));
    }

    /* ══════════════════════════════════════════════════════════════════════
       The run
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.PayrollDto> search(String status, Pageable pageable) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see payroll");
        return PageResponse.from(
                payrollRepo.search(scope.branchId(),
                                   status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(p -> mapPayroll(p, false)));
    }

    @Transactional(readOnly = true)
    public HrResponses.PayrollDto get(Long payrollId) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see payroll");
        return mapPayroll(payroll(payrollId), true);
    }

    /**
     * Calculates, or recalculates, the run for a period.
     *
     * <p>The whole thing is one transaction. A payroll half-written is a
     * payroll that pays half the shop and cannot say which half — and the
     * release-then-reclaim of loan installments in the middle of it is only
     * safe because nothing else can see the intermediate state.
     */
    @Transactional
    public HrResponses.PayrollDto calculate(HrRequests.PayrollCalculate req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.CREATE, "calculate payroll");

        HrPayrollPeriod period = period(req.getPeriodId());
        if (!period.isOpen()) {
            throw new BusinessException(
                    period.getLabel() + " is closed. Reopen it before calculating.",
                    HttpStatus.CONFLICT, "HR_PERIOD_CLOSED");
        }

        Long branch = scope.branchId();
        HrPayroll run = payrollRepo.findLiveRun(branch, period.getPidPeriod()).orElse(null);
        if (run == null) {
            run = HrPayroll.builder()
                    .pidOrg(scope.orgId())
                    .pidCompany(scope.companyId())
                    .pidBranch(branch)
                    .pidPeriod(period.getPidPeriod())
                    .payrollNo(documentNumbers.nextInvoiceNo(PAYROLL_PREFIX))
                    .payrollStatus(HrConstants.PayrollStatus.DRAFT)
                    .build();
            run.setStatus("Y");
            payrollRepo.save(run);
        } else if (run.isFrozen()) {
            throw new BusinessException(
                    "Payroll " + run.getPayrollNo() + " has been posted and cannot be recalculated. "
                    + "Correct it with a reversal and a fresh run.",
                    HttpStatus.CONFLICT, "HR_PAYROLL_POSTED");
        }

        releasePreviousClaims(run);

        /* Clear, then FLUSH, then insert.

           uq_hr_payroll_line is on (pid_payroll, pid_employee) and Hibernate
           orders a transaction's inserts before its deletes. Without the flush,
           recalculating a run inserts the new payslip while the old one is
           still there and the index refuses it — so a second Calculate on the
           same run failed with what looked like a duplicate payslip and was
           really a flush-order problem. The same trap the working policy, the
           PF scheme and the salary structure's components all hit. */
        run.getLines().clear();
        payrollRepo.saveAndFlush(run);

        /* ── One read each, for the whole branch ── */
        List<HrEmployee> staff = payrollReadyStaff(branch, req.getEmployeeIds());
        /* The provident fund scheme that governed the period, not the one in
           force today: a scheme changed mid-month did not govern the month it
           was changed in. Null when the branch has none, which is the shipped
           state and means no PF comes off anybody's payslip. */
        HrPfScheme pfScheme = pf.schemeOn(branch, period.getPeriodTo());
        Map<Long, Attendance> attendance = attendanceByEmployee(branch, period);
        Map<Long, LeaveDays>  leave      = leaveByEmployee(branch, period);
        Map<Long, Structure>  structures = structuresFor(staff);
        Map<Long, List<HrAdvance>> advances = advancesByEmployee(branch, period.getPeriodTo());
        Map<Long, List<HrLoanSchedule>> installments = installmentsByEmployee(branch, period, run);

        Map<Long, BigDecimal> bonuses = req.getBonuses() == null ? Map.of() : req.getBonuses();

        int count = 0;
        BigDecimal totalGross = BigDecimal.ZERO, totalDeduction = BigDecimal.ZERO,
                   totalTax   = BigDecimal.ZERO, totalNet       = BigDecimal.ZERO;

        for (HrEmployee e : staff) {
            Structure s = structures.get(e.getPidSalaryStructure());
            if (s == null) continue;   // guarded by payrollReadyStaff, kept as a floor

            Attendance att = attendance.getOrDefault(e.getPidEmployee(), Attendance.EMPTY);
            LeaveDays  lv  = leave.getOrDefault(e.getPidEmployee(), LeaveDays.EMPTY);

            List<HrAdvance> owedAdvances = advances.getOrDefault(e.getPidEmployee(), List.of());
            List<HrLoanSchedule> owedLoan = installments.getOrDefault(e.getPidEmployee(), List.of());

            BigDecimal advanceDue = BigDecimal.ZERO;
            for (HrAdvance a : owedAdvances) advanceDue = advanceDue.add(a.getInstallmentAmount());
            BigDecimal loanDue = BigDecimal.ZERO;
            for (HrLoanSchedule i : owedLoan) loanDue = loanDue.add(i.getRemaining());

            PayrollCalculator.Result r = PayrollCalculator.calculate(new PayrollCalculator.Input(
                    e.getBasicSalary(),
                    period.getWorkingDays(),
                    att.absent,
                    lv.unpaid,
                    att.overtimeMinutes,
                    bonuses.get(e.getPidEmployee()),
                    s.hoursPerDay,
                    s.overtimeMultiplier,
                    s.absenceBasis,
                    advanceDue,
                    loanDue,
                    pf.termsFor(pfScheme, e, period.getPeriodTo()),
                    s.components));

            HrPayrollLine line = writeLine(run, e, att, lv, r);

            // What was actually taken is written back, not what was asked for:
            // the calculator caps a recovery at what the month contained.
            applyAdvanceRecovery(owedAdvances, r.advanceRecovery());
            applyLoanRecovery(owedLoan, r.loanRecovery(), run, period.getPeriodTo());

            run.getLines().add(line);
            count++;
            totalGross     = totalGross.add(r.grossSalary());
            totalDeduction = totalDeduction.add(r.deductionTotal());
            totalTax       = totalTax.add(r.taxAmount());
            totalNet       = totalNet.add(r.netSalary());
        }

        run.setEmployeeCount(count);
        run.setTotalGross(totalGross);
        run.setTotalDeduction(totalDeduction);
        run.setTotalTax(totalTax);
        run.setTotalNet(totalNet);
        run.setPayrollStatus(HrConstants.PayrollStatus.CALCULATED);
        run.setCalculatedBy(scope.actor());
        run.setCalculatedAt(LocalDateTime.now());
        if (req.getRemarks() != null) run.setRemarks(req.getRemarks());
        payrollRepo.save(run);

        log.info("Payroll {} calculated for branch {}: {} payslips, net {}",
                 run.getPayrollNo(), branch, count, totalNet);
        return mapPayroll(run, true);
    }

    /* ── The three signatures ── */

    @Transactional
    public HrResponses.PayrollDto review(Long payrollId, HrRequests.Decision d) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT, "review payroll");
        HrPayroll run = payroll(payrollId);
        requireState(run, HrConstants.PayrollStatus.CALCULATED);
        run.setPayrollStatus(HrConstants.PayrollStatus.REVIEWED);
        run.setReviewedBy(scope.actor());
        run.setReviewedAt(LocalDateTime.now());
        if (d != null && d.getRemarks() != null) run.setRemarks(d.getRemarks());
        return mapPayroll(payrollRepo.save(run), true);
    }

    /**
     * Approves a reviewed run.
     *
     * <p>Approve is a separate right from Create for a reason: the person who
     * prepares a payroll should not be the person who signs it off, and the
     * permission matrix is where that separation is expressed. V77 grants
     * Approve to ROLE01 and ROLE02 and withholds it from ROLE04 Accounts.
     */
    @Transactional
    public HrResponses.PayrollDto approve(Long payrollId, HrRequests.Decision d) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.APPROVE, "approve payroll");
        HrPayroll run = payroll(payrollId);
        requireState(run, HrConstants.PayrollStatus.REVIEWED);
        run.setPayrollStatus(HrConstants.PayrollStatus.APPROVED);
        run.setApprovedBy(scope.actor());
        run.setApprovedAt(LocalDateTime.now());
        if (d != null && d.getRemarks() != null) run.setRemarks(d.getRemarks());
        return mapPayroll(payrollRepo.save(run), true);
    }

    /**
     * Posts the run, which freezes it.
     *
     * <p>From here the row is read-only. Nothing accounting happens yet:
     * Accounts does not exist in this build, and this is the point its
     * financial event will be raised from when it does — see
     * {@code 10-payroll-accounting.md}. Deliberately a separate step from
     * approval so that adding the accounting hook later changes one method and
     * not the lifecycle.
     */
    @Transactional
    public HrResponses.PayrollDto post(Long payrollId, HrRequests.Decision d) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.APPROVE, "post payroll");
        HrPayroll run = payroll(payrollId);
        requireState(run, HrConstants.PayrollStatus.APPROVED);

        run.setPayrollStatus(HrConstants.PayrollStatus.POSTED);
        run.setPostedBy(scope.actor());
        run.setPostedAt(LocalDateTime.now());
        if (d != null && d.getRemarks() != null) run.setRemarks(d.getRemarks());
        payrollRepo.save(run);
        accounting.payrollPosted(run);

        /* The provident fund's copy, written here rather than at calculation
           because a run can be recalculated any number of times before this
           point and a fund whose balance moved each time would not be a fund.
           Idempotent: a run that already contributed writes nothing. */
        HrPayrollPeriod period = periodRepo.findById(run.getPidPeriod()).orElse(null);
        HrPfScheme scheme = period == null ? null : pf.schemeOn(run.getPidBranch(), period.getPeriodTo());
        int contributions = pf.recordContributions(
                run, period, scheme == null ? null : scheme.getPidPfScheme());

        log.info("Payroll {} posted: {} payslips, net {}, {} PF contributions "
                 + "— no journal written, Accounts not yet present",
                 run.getPayrollNo(), run.getEmployeeCount(), run.getTotalNet(), contributions);
        return mapPayroll(run, true);
    }

    /**
     * Cancels a run that has not been posted.
     *
     * <p>The row stays: it is evidence that a month was started and abandoned,
     * and {@code uq_hr_payroll_live_run} excludes cancelled runs so the month
     * can be started again. Everything the run claimed is released first, or
     * the loan installments it took would stay taken by a run that no longer
     * pays anybody.
     */
    @Transactional
    public HrResponses.PayrollDto cancel(Long payrollId, HrRequests.Decision d) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.DELETE, "cancel payroll");

        HrPayroll run = payroll(payrollId);
        if (run.isFrozen()) {
            throw new BusinessException(
                    "Payroll " + run.getPayrollNo() + " has been posted and cannot be cancelled. "
                    + "Correct it with a reversal and a fresh run.",
                    HttpStatus.CONFLICT, "HR_PAYROLL_POSTED");
        }
        releasePreviousClaims(run);

        run.setPayrollStatus(HrConstants.PayrollStatus.CANCELLED);
        run.setCancelledBy(scope.actor());
        run.setCancelledAt(LocalDateTime.now());
        run.setCancelReason(d == null ? null : d.getRemarks());
        return mapPayroll(payrollRepo.save(run), false);
    }

    /* ══════════════════════════════════════════════════════════════════════
       Salary payment
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Records money handed over against a posted run.
     *
     * <p>Only a posted run can be paid. Paying a run that has not been approved
     * would put money out against figures nobody signed for.
     *
     * <p>{@code payrollLineId} null pays the whole run — one row per payslip,
     * so cancelling one person's payment later does not unpick everyone's.
     */
    @Transactional
    public List<HrResponses.SalaryPaymentDto> pay(HrRequests.SalaryPayment req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT, "record a salary payment");

        HrPayroll run = payroll(req.getPayrollId());
        if (!HrConstants.PayrollStatus.isFrozen(run.getPayrollStatus())) {
            throw new BusinessException(
                    "Payroll " + run.getPayrollNo() + " is "
                    + HrConstants.label(run.getPayrollStatus()).toLowerCase()
                    + ". Post it before paying anybody.",
                    HttpStatus.CONFLICT, "HR_PAYROLL_NOT_POSTED");
        }

        String mode = HrConstants.normalise(HrConstants.PaymentMode.ALL, req.getPaymentMode());
        if (mode == null) {
            throw new BusinessException(
                    "'" + req.getPaymentMode() + "' is not a payment method. Use one of: "
                    + String.join(", ", HrConstants.PaymentMode.ALL) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        LocalDate on = req.getPaymentDate() == null ? LocalDate.now() : req.getPaymentDate();

        List<HrPayrollLine> targets = req.getPayrollLineId() != null
                ? List.of(oneLine(run, req.getPayrollLineId()))
                : lineRepo.findUnpaid(run.getPidPayroll());

        if (targets.isEmpty()) {
            throw new BusinessException("Everybody on this run has already been paid.",
                    HttpStatus.CONFLICT, "HR_PAYROLL_FULLY_PAID");
        }

        List<HrResponses.SalaryPaymentDto> made = new ArrayList<>();
        for (HrPayrollLine line : targets) {
            BigDecimal owing = line.getUnpaid();
            if (owing.signum() <= 0) continue;

            // An amount is only honoured when one payslip was named. Spreading
            // a single figure across a whole run would need a rule nobody has
            // asked for, and guessing one changes what people receive.
            BigDecimal amount = (req.getPayrollLineId() != null && req.getAmount() != null)
                    ? req.getAmount() : owing;
            if (amount.compareTo(owing) > 0) {
                throw new BusinessException(
                        line.getEmpName() + " is owed " + owing + " on this run, and that is "
                        + "more than the payment offered covers.",
                        HttpStatus.BAD_REQUEST, "HR_PAYMENT_OVER_NET");
            }

            HrSalaryPayment p = HrSalaryPayment.builder()
                    .pidOrg(run.getPidOrg())
                    .pidCompany(run.getPidCompany())
                    .pidBranch(run.getPidBranch())
                    .pidPayroll(run.getPidPayroll())
                    .pidPayrollLine(line.getPidPayrollLine())
                    .pidEmployee(line.getPidEmployee())
                    .voucherNo(documentNumbers.nextInvoiceNo(VOUCHER_PREFIX))
                    .paymentDate(on)
                    .amount(amount)
                    .paymentMode(mode)
                    .bankName(req.getBankName())
                    .chequeNo(req.getChequeNo())
                    .chequeDate(req.getChequeDate())
                    .referenceNo(req.getReferenceNo())
                    .remarks(req.getRemarks())
                    .paymentStatus(HrSalaryPayment.PAID)
                    .build();
            p.setStatus("Y");
            paymentRepo.save(p);
            accounting.salaryPaid(p, scope.orgId());

            line.setPaidAmount(nz(line.getPaidAmount()).add(amount));
            line.setPaymentStatus(line.getUnpaid().signum() <= 0
                    ? HrConstants.PaySlipStatus.PAID : HrConstants.PaySlipStatus.PARTIAL);
            lineRepo.save(line);

            made.add(mapPayment(p, line));
        }

        refreshPaidTotal(run);
        return made;
    }

    @Transactional
    public List<HrResponses.SalaryPaymentDto> payments(Long payrollId) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see salary payments");
        HrPayroll run = payroll(payrollId);
        Map<Long, HrPayrollLine> lines = new HashMap<>();
        for (HrPayrollLine l : lineRepo.findByPayroll_PidPayrollOrderByEmpName(run.getPidPayroll())) {
            lines.put(l.getPidPayrollLine(), l);
        }
        return paymentRepo.findByPidPayrollAndStatusOrderByPaymentDateDesc(payrollId, "Y")
                .stream().map(p -> mapPayment(p, lines.get(p.getPidPayrollLine()))).toList();
    }

    /**
     * Cancels a payment.
     *
     * <p>The payslip goes back to owing what it owed. The payroll total is then
     * recomputed by summing the live payments rather than by subtracting — a
     * running total maintained by two operations is a total that eventually
     * disagrees with the rows behind it.
     */
    @Transactional
    public HrResponses.SalaryPaymentDto cancelPayment(Long paymentId, HrRequests.Decision d) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.DELETE, "cancel a salary payment");

        HrSalaryPayment p = paymentRepo.findByPidSalaryPaymentAndPidBranch(paymentId, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Salary payment", paymentId));
        if (!p.isPaid()) {
            throw new BusinessException("This payment has already been cancelled.",
                    HttpStatus.CONFLICT, "HR_PAYMENT_ALREADY_CANCELLED");
        }
        p.setPaymentStatus(HrSalaryPayment.CANCELLED);
        p.setCancelReason(d == null ? null : d.getRemarks());
        paymentRepo.save(p);
        accounting.salaryPaymentCancelled(p, scope.orgId(), d == null ? null : d.getRemarks());

        HrPayrollLine line = null;
        if (p.getPidPayrollLine() != null) {
            line = lineRepo.findById(p.getPidPayrollLine()).orElse(null);
            if (line != null) {
                line.setPaidAmount(floorAtZero(nz(line.getPaidAmount()).subtract(p.getAmount())));
                line.setPaymentStatus(line.getPaidAmount().signum() == 0
                        ? HrConstants.PaySlipStatus.UNPAID
                        : (line.getUnpaid().signum() <= 0
                                ? HrConstants.PaySlipStatus.PAID
                                : HrConstants.PaySlipStatus.PARTIAL));
                lineRepo.save(line);
            }
        }
        refreshPaidTotal(payroll(p.getPidPayroll()));
        return mapPayment(p, line);
    }

    /** Sums the live payments and moves the run to PAID when nothing is owing. */
    private void refreshPaidTotal(HrPayroll run) {
        BigDecimal paid = nz(paymentRepo.sumPaidFor(run.getPidPayroll()));
        run.setTotalPaid(paid);
        if (HrConstants.PayrollStatus.POSTED.equals(run.getPayrollStatus())
                && paid.compareTo(nz(run.getTotalNet())) >= 0
                && nz(run.getTotalNet()).signum() > 0) {
            run.setPayrollStatus(HrConstants.PayrollStatus.PAID);
        } else if (HrConstants.PayrollStatus.PAID.equals(run.getPayrollStatus())
                && paid.compareTo(nz(run.getTotalNet())) < 0) {
            // A cancelled payment takes the run back out of PAID; otherwise the
            // list would show a run as settled while somebody is still owed.
            run.setPayrollStatus(HrConstants.PayrollStatus.POSTED);
        }
        payrollRepo.save(run);
    }

    /* ══════════════════════════════════════════════════════════════════════
       Gathering the inputs
       ══════════════════════════════════════════════════════════════════════ */

    /** Active staff with a salary and a structure. Everybody else is skipped. */
    private List<HrEmployee> payrollReadyStaff(Long branch, List<Long> only) {
        List<HrEmployee> all = employeeRepo.findByPidBranchAndStatusOrderByEmpName(branch, "Y");
        List<HrEmployee> out = new ArrayList<>();
        for (HrEmployee e : all) {
            if (!e.isPayrollReady()) continue;
            if (only != null && !only.isEmpty() && !only.contains(e.getPidEmployee())) continue;
            out.add(e);
        }
        if (out.isEmpty()) {
            throw new BusinessException(
                    "Nobody at this branch is ready for payroll. An employee needs a basic salary "
                    + "and a salary structure before they can be paid.",
                    HttpStatus.CONFLICT, "HR_NO_PAYROLL_READY_STAFF");
        }
        return out;
    }

    /** Days present, absent and overtime, counted from attendance in one read. */
    private Map<Long, Attendance> attendanceByEmployee(Long branch, HrPayrollPeriod period) {
        Map<Long, Attendance> out = new HashMap<>();
        for (HrAttendance a : attendanceRepo.findBranchRange(
                branch, period.getPeriodFrom(), period.getPeriodTo())) {
            Attendance acc = out.computeIfAbsent(a.getPidEmployee(), k -> new Attendance());
            switch (a.getAttendanceStatus()) {
                case HrConstants.AttendanceStatus.PRESENT,
                     HrConstants.AttendanceStatus.LATE,
                     HrConstants.AttendanceStatus.EARLY_LEAVE -> acc.present = acc.present.add(BigDecimal.ONE);
                case HrConstants.AttendanceStatus.HALF_DAY -> {
                    acc.present = acc.present.add(HALF);
                    acc.absent  = acc.absent.add(HALF);
                }
                case HrConstants.AttendanceStatus.ABSENT -> acc.absent = acc.absent.add(BigDecimal.ONE);
                case HrConstants.AttendanceStatus.LEAVE  -> acc.leave  = acc.leave.add(BigDecimal.ONE);
                default -> { /* HOLIDAY and WEEKEND are neither worked nor absent */ }
            }
            acc.overtimeMinutes += a.getOvertimeMinutes() == null ? 0 : a.getOvertimeMinutes();
        }
        return out;
    }

    /**
     * Approved leave inside the period, split paid from unpaid.
     *
     * <p>Only the unpaid days reach the calculator — those are the ones valued
     * and deducted. Paid leave is an absence the employer has already agreed to
     * pay for, so it changes the payslip's day counts and not its money.
     */
    private Map<Long, LeaveDays> leaveByEmployee(Long branch, HrPayrollPeriod period) {
        Map<Long, HrLeaveType> types = new HashMap<>();
        for (HrLeaveType t : leaveTypeRepo.findByPidBranchOrderByTypeName(branch)) {
            types.put(t.getPidLeaveType(), t);
        }

        Map<Long, LeaveDays> out = new HashMap<>();
        for (HrLeaveRequest r : leaveRepo.findApprovedInRange(
                branch, period.getPeriodFrom(), period.getPeriodTo())) {

            // Only the part of the request that falls inside this period. Leave
            // spanning a month boundary belongs to both months, in proportion.
            LocalDate from = r.getFromDate().isBefore(period.getPeriodFrom())
                    ? period.getPeriodFrom() : r.getFromDate();
            LocalDate to = r.getToDate().isAfter(period.getPeriodTo())
                    ? period.getPeriodTo() : r.getToDate();
            if (to.isBefore(from)) continue;

            BigDecimal days = (r.getIsHalfDay() != null && r.getIsHalfDay() == 1)
                    ? HALF
                    : BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1);

            LeaveDays acc = out.computeIfAbsent(r.getPidEmployee(), k -> new LeaveDays());
            HrLeaveType t = types.get(r.getPidLeaveType());
            if (t != null && !t.isPaidLeave()) acc.unpaid = acc.unpaid.add(days);
            else                               acc.paid   = acc.paid.add(days);
        }
        return out;
    }

    /** Structures and their components, keyed by structure, in two reads. */
    private Map<Long, Structure> structuresFor(List<HrEmployee> staff) {
        Set<Long> ids = new HashSet<>();
        for (HrEmployee e : staff) if (e.getPidSalaryStructure() != null) ids.add(e.getPidSalaryStructure());
        if (ids.isEmpty()) return Map.of();

        Map<Long, Structure> out = new HashMap<>();
        for (HrSalaryStructure s : structureRepo.findAllById(ids)) {
            Structure holder = new Structure();
            holder.hoursPerDay        = s.getStandardHoursPerDay();
            holder.overtimeMultiplier = s.getOvertimeMultiplier();
            holder.absenceBasis       = s.getAbsenceBasis();
            holder.components         = new ArrayList<>();
            out.put(s.getPidStructure(), holder);
        }
        for (HrSalaryComponent c : componentRepo.findByStructure_PidStructureInAndStatus(
                new ArrayList<>(ids), "Y")) {
            Structure holder = out.get(c.getStructure().getPidStructure());
            if (holder == null) continue;
            holder.components.add(new PayrollCalculator.Component(
                    c.getComponentCode(), c.getComponentName(), c.getComponentType(),
                    c.getCalcMethod(), c.getAmount(), c.getPctValue(),
                    c.getIsTaxable() != null && c.getIsTaxable() == 1,
                    c.getIsPf() != null && c.getIsPf() == 1,
                    c.getSortOrder() == null ? 100 : c.getSortOrder()));
        }
        return out;
    }

    private Map<Long, List<HrAdvance>> advancesByEmployee(Long branch, LocalDate upto) {
        Map<Long, List<HrAdvance>> out = new HashMap<>();
        for (HrAdvance a : advanceRepo.findRecoverable(branch, upto)) {
            if (!a.isRecoverable()) continue;
            out.computeIfAbsent(a.getPidEmployee(), k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    /**
     * Loan installments this run should take.
     *
     * <p>Due by the end of the period, still open, and either unclaimed or
     * already claimed by this same run — the last condition is what makes a
     * recalculation reproduce the first answer instead of a smaller one.
     */
    private Map<Long, List<HrLoanSchedule>> installmentsByEmployee(
            Long branch, HrPayrollPeriod period, HrPayroll run) {

        List<HrLoan> loans = loanRepo.findRecoverable(branch);
        if (loans.isEmpty()) return Map.of();

        Map<Long, Long> employeeOfLoan = new HashMap<>();
        List<Long> loanIds = new ArrayList<>();
        for (HrLoan l : loans) {
            loanIds.add(l.getPidLoan());
            employeeOfLoan.put(l.getPidLoan(), l.getPidEmployee());
        }

        Map<Long, List<HrLoanSchedule>> out = new HashMap<>();
        for (HrLoanSchedule s : scheduleRepo.findDueFor(loanIds, period.getPeriodTo(), run.getPidPayroll())) {
            Long employeeId = employeeOfLoan.get(s.getLoan().getPidLoan());
            if (employeeId == null) continue;
            out.computeIfAbsent(employeeId, k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       Writing the results back
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Undoes everything a previous calculation of this run claimed.
     *
     * <p>Without this, recalculating produces a smaller payslip than the first
     * pass: the installments and advance amounts the first pass took are still
     * marked as taken, so the second pass finds nothing to recover and the
     * employee is paid the recovery back by accident.
     */
    private void releasePreviousClaims(HrPayroll run) {
        for (HrLoanSchedule s : scheduleRepo.findByPidPayroll(run.getPidPayroll())) {
            HrLoan l = s.getLoan();
            l.setRecoveredAmount(floorAtZero(nz(l.getRecoveredAmount()).subtract(nz(s.getRecoveredAmount()))));
            if (HrConstants.LoanStatus.CLOSED.equals(l.getLoanStatus())) {
                l.setLoanStatus(HrConstants.LoanStatus.RECOVERING);
            }
            loanRepo.save(l);

            s.setRecoveredAmount(BigDecimal.ZERO);
            s.setScheduleStatus(HrConstants.ScheduleStatus.PENDING);
            s.setPidPayroll(null);
            s.setRecoveredOn(null);
            scheduleRepo.save(s);
        }

        for (HrPayrollLine line : lineRepo.findByPayroll_PidPayrollOrderByEmpName(run.getPidPayroll())) {
            if (nz(line.getAdvanceRecovery()).signum() == 0) continue;
            BigDecimal giveBack = line.getAdvanceRecovery();
            for (HrAdvance a : advanceRepo.findByPidEmployeeAndStatusOrderByRequestDateDesc(
                    line.getPidEmployee(), "Y")) {
                if (giveBack.signum() <= 0) break;
                BigDecimal taken = nz(a.getRecoveredAmount());
                if (taken.signum() <= 0) continue;
                BigDecimal undo = taken.min(giveBack);
                a.setRecoveredAmount(taken.subtract(undo));
                if (HrConstants.AdvanceStatus.CLOSED.equals(a.getAdvanceStatus())) {
                    a.setAdvanceStatus(HrConstants.AdvanceStatus.RECOVERING);
                }
                advanceRepo.save(a);
                giveBack = giveBack.subtract(undo);
            }
        }
    }

    /** Spreads what was actually recovered across the advances, oldest first. */
    private void applyAdvanceRecovery(List<HrAdvance> advances, BigDecimal recovered) {
        BigDecimal left = nz(recovered);
        for (HrAdvance a : advances) {
            if (left.signum() <= 0) break;
            BigDecimal take = a.getOutstanding().min(left);
            if (take.signum() <= 0) continue;

            a.setRecoveredAmount(nz(a.getRecoveredAmount()).add(take));
            a.setAdvanceStatus(a.getOutstanding().signum() <= 0
                    ? HrConstants.AdvanceStatus.CLOSED : HrConstants.AdvanceStatus.RECOVERING);
            advanceRepo.save(a);
            left = left.subtract(take);
        }
    }

    /** Spreads what was actually recovered across the installments, in order. */
    private void applyLoanRecovery(List<HrLoanSchedule> installments, BigDecimal recovered,
                                   HrPayroll run, LocalDate on) {
        BigDecimal left = nz(recovered);
        for (HrLoanSchedule s : installments) {
            if (left.signum() <= 0) break;
            BigDecimal take = s.getRemaining().min(left);
            if (take.signum() <= 0) continue;

            s.setRecoveredAmount(nz(s.getRecoveredAmount()).add(take));
            s.setScheduleStatus(s.getRemaining().signum() <= 0
                    ? HrConstants.ScheduleStatus.RECOVERED : HrConstants.ScheduleStatus.PARTIAL);
            s.setPidPayroll(run.getPidPayroll());
            s.setRecoveredOn(on);
            scheduleRepo.save(s);

            HrLoan l = s.getLoan();
            l.setRecoveredAmount(nz(l.getRecoveredAmount()).add(take));
            l.setLoanStatus(l.getOutstanding().signum() <= 0
                    ? HrConstants.LoanStatus.CLOSED : HrConstants.LoanStatus.RECOVERING);
            loanRepo.save(l);

            left = left.subtract(take);
        }
    }

    /** One payslip, with the arithmetic that produced it. */
    private HrPayrollLine writeLine(HrPayroll run, HrEmployee e, Attendance att, LeaveDays lv,
                                    PayrollCalculator.Result r) {
        HrPayrollLine line = HrPayrollLine.builder()
                .payroll(run)
                .pidEmployee(e.getPidEmployee())
                .pidDepartment(e.getPidDepartment())
                .pidDesignation(e.getPidDesignation())
                .empId(e.getEmpId())
                .empName(e.getEmpName())
                .basicSalary(r.basic())
                .allowanceTotal(r.allowanceTotal())
                .overtimeAmount(r.overtimeAmount())
                .bonusAmount(r.bonusAmount())
                .grossSalary(r.grossSalary())
                .deductionTotal(r.deductionTotal())
                .absenceDeduction(r.absenceDeduction())
                .advanceRecovery(r.advanceRecovery())
                .loanRecovery(r.loanRecovery())
                .pfEmployeeAmount(r.pfEmployee())
                .pfEmployerAmount(r.pfEmployer())
                .taxAmount(r.taxAmount())
                .netSalary(r.netSalary())
                .presentDays(att.present)
                .absentDays(att.absent)
                .leaveDays(lv.paid.add(lv.unpaid))
                .unpaidLeaveDays(lv.unpaid)
                .overtimeMinutes(att.overtimeMinutes)
                .paymentStatus(HrConstants.PaySlipStatus.UNPAID)
                .build();
        line.setStatus("Y");

        for (PayrollCalculator.ResultLine rl : r.lines()) {
            HrPayslipComponent c = HrPayslipComponent.builder()
                    .payrollLine(line)
                    .componentCode(rl.code())
                    .componentName(rl.name())
                    .componentType(rl.type())
                    .calcMethod(rl.method())
                    .baseAmount(rl.base())
                    .pctValue(rl.pct())
                    .amount(rl.amount())
                    .sortOrder(rl.sortOrder())
                    .build();
            c.setStatus("Y");
            line.getComponents().add(c);
        }
        return line;
    }

    /* ══ Lookups, guards, mapping ═══════════════════════════════════════ */

    private HrPayrollPeriod period(Long id) {
        return periodRepo.findByPidPeriodAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Payroll period", id));
    }

    private HrPayroll payroll(Long id) {
        return payrollRepo.findByPidPayrollAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Payroll", id));
    }

    private HrPayrollLine oneLine(HrPayroll run, Long lineId) {
        return lineRepo.findById(lineId)
                .filter(l -> l.getPayroll().getPidPayroll().equals(run.getPidPayroll()))
                .orElseThrow(() -> new ResourceNotFoundException("Payslip", lineId));
    }

    private static void requireState(HrPayroll run, String wanted) {
        if (!wanted.equals(run.getPayrollStatus())) {
            throw new BusinessException(
                    "Payroll " + run.getPayrollNo() + " is "
                    + HrConstants.label(run.getPayrollStatus()).toLowerCase()
                    + ", and that step needs it to be " + HrConstants.label(wanted).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_WRONG_STATE");
        }
    }

    /**
     * What the caller may do next.
     *
     * <p>Sent with the run so the screen does not hold a second copy of the
     * lifecycle. A button the server will refuse is worse than no button.
     */
    private List<String> allowedActions(HrPayroll run) {
        List<String> out = new ArrayList<>();
        String s = run.getPayrollStatus();
        if (HrConstants.PayrollStatus.DRAFT.equals(s)
                || HrConstants.PayrollStatus.CALCULATED.equals(s)
                || HrConstants.PayrollStatus.REVIEWED.equals(s)) {
            if (scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.CREATE)) out.add("CALCULATE");
        }
        if (HrConstants.PayrollStatus.CALCULATED.equals(s)
                && scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT)) out.add("REVIEW");
        if (HrConstants.PayrollStatus.REVIEWED.equals(s)
                && scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.APPROVE)) out.add("APPROVE");
        if (HrConstants.PayrollStatus.APPROVED.equals(s)
                && scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.APPROVE)) out.add("POST");
        if (HrConstants.PayrollStatus.isFrozen(s) && run.getUnpaid().signum() > 0
                && scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT)) out.add("PAY");
        if (!HrConstants.PayrollStatus.isFrozen(s) && !run.isCancelled()
                && scope.has(HrScope.MOD_PAYROLL, PermissionGuard.Right.DELETE)) out.add("CANCEL");
        return out;
    }

    private HrResponses.PayrollPeriodDto mapPeriod(HrPayrollPeriod p) {
        HrPayroll run = payrollRepo.findLiveRun(p.getPidBranch(), p.getPidPeriod()).orElse(null);
        return HrResponses.PayrollPeriodDto.builder()
                .id(p.getPidPeriod())
                .periodYear(p.getPeriodYear())
                .periodMonth(p.getPeriodMonth())
                .label(p.getLabel())
                .periodFrom(p.getPeriodFrom())
                .periodTo(p.getPeriodTo())
                .workingDays(p.getWorkingDays())
                .periodStatus(p.getPeriodStatus())
                .remarks(p.getRemarks())
                .payrollId(run == null ? null : run.getPidPayroll())
                .payrollStatus(run == null ? null : run.getPayrollStatus())
                .build();
    }

    private HrResponses.PayrollDto mapPayroll(HrPayroll run, boolean withLines) {
        HrPayrollPeriod p = periodRepo.findById(run.getPidPeriod()).orElse(null);

        List<HrResponses.PayslipDto> slips = null;
        if (withLines) {
            slips = lineRepo.findByPayroll_PidPayrollOrderByEmpName(run.getPidPayroll())
                    .stream().map(this::mapPayslip).toList();
        }

        return HrResponses.PayrollDto.builder()
                .id(run.getPidPayroll())
                .payrollNo(run.getPayrollNo())
                .periodId(run.getPidPeriod())
                .periodLabel(p == null ? null : p.getLabel())
                .periodFrom(p == null ? null : p.getPeriodFrom())
                .periodTo(p == null ? null : p.getPeriodTo())
                .workingDays(p == null ? null : p.getWorkingDays())
                .payrollStatus(run.getPayrollStatus())
                .payrollStatusLabel(HrConstants.label(run.getPayrollStatus()))
                .employeeCount(run.getEmployeeCount())
                .totalGross(run.getTotalGross())
                .totalDeduction(run.getTotalDeduction())
                .totalTax(run.getTotalTax())
                .totalNet(run.getTotalNet())
                .totalPaid(run.getTotalPaid())
                .unpaid(run.getUnpaid())
                .calculatedBy(run.getCalculatedBy()).calculatedAt(run.getCalculatedAt())
                .reviewedBy(run.getReviewedBy())     .reviewedAt(run.getReviewedAt())
                .approvedBy(run.getApprovedBy())     .approvedAt(run.getApprovedAt())
                .postedBy(run.getPostedBy())         .postedAt(run.getPostedAt())
                .cancelledBy(run.getCancelledBy())   .cancelledAt(run.getCancelledAt())
                .cancelReason(run.getCancelReason())
                .remarks(run.getRemarks())
                .frozen(run.isFrozen())
                .allowedActions(allowedActions(run))
                .lines(slips)
                .build();
    }

    HrResponses.PayslipDto mapPayslip(HrPayrollLine l) {
        List<HrResponses.PayslipLineDto> comps = l.getComponents().stream()
                .map(c -> HrResponses.PayslipLineDto.builder()
                        .componentCode(c.getComponentCode())
                        .componentName(c.getComponentName())
                        .componentType(c.getComponentType())
                        .calcMethod(c.getCalcMethod())
                        .baseAmount(c.getBaseAmount())
                        .pctValue(c.getPctValue())
                        .amount(c.getAmount())
                        .sortOrder(c.getSortOrder())
                        .build())
                .toList();

        return HrResponses.PayslipDto.builder()
                .id(l.getPidPayrollLine())
                .employeeId(l.getPidEmployee())
                .empId(l.getEmpId())
                .empName(l.getEmpName())
                .departmentId(l.getPidDepartment())
                .designationId(l.getPidDesignation())
                .basicSalary(l.getBasicSalary())
                .allowanceTotal(l.getAllowanceTotal())
                .overtimeAmount(l.getOvertimeAmount())
                .bonusAmount(l.getBonusAmount())
                .grossSalary(l.getGrossSalary())
                .deductionTotal(l.getDeductionTotal())
                .absenceDeduction(l.getAbsenceDeduction())
                .advanceRecovery(l.getAdvanceRecovery())
                .loanRecovery(l.getLoanRecovery())
                .pfEmployeeAmount(l.getPfEmployeeAmount())
                .pfEmployerAmount(l.getPfEmployerAmount())
                .taxAmount(l.getTaxAmount())
                .netSalary(l.getNetSalary())
                .presentDays(l.getPresentDays())
                .absentDays(l.getAbsentDays())
                .leaveDays(l.getLeaveDays())
                .unpaidLeaveDays(l.getUnpaidLeaveDays())
                .overtimeMinutes(l.getOvertimeMinutes())
                .overtimeHours(HrAttendance.hours(l.getOvertimeMinutes()))
                .paymentStatus(l.getPaymentStatus())
                .paidAmount(l.getPaidAmount())
                .unpaid(l.getUnpaid())
                .remarks(l.getRemarks())
                .components(comps)
                .build();
    }

    private HrResponses.SalaryPaymentDto mapPayment(HrSalaryPayment p, HrPayrollLine line) {
        return HrResponses.SalaryPaymentDto.builder()
                .id(p.getPidSalaryPayment())
                .voucherNo(p.getVoucherNo())
                .payrollId(p.getPidPayroll())
                .payrollLineId(p.getPidPayrollLine())
                .employeeId(p.getPidEmployee())
                .empName(line == null ? null : line.getEmpName())
                .paymentDate(p.getPaymentDate())
                .amount(p.getAmount())
                .paymentMode(p.getPaymentMode())
                .paymentModeLabel(HrConstants.label(p.getPaymentMode()))
                .bankName(p.getBankName())
                .chequeNo(p.getChequeNo())
                .chequeDate(p.getChequeDate())
                .referenceNo(p.getReferenceNo())
                .remarks(p.getRemarks())
                .paymentStatus(p.getPaymentStatus())
                .cancelReason(p.getCancelReason())
                .build();
    }

    /* ══ Small carriers, so the loop reads as arithmetic ════════════════ */

    private static final class Attendance {
        static final Attendance EMPTY = new Attendance();
        BigDecimal present = BigDecimal.ZERO;
        BigDecimal absent  = BigDecimal.ZERO;
        BigDecimal leave   = BigDecimal.ZERO;
        int overtimeMinutes = 0;
    }

    private static final class LeaveDays {
        static final LeaveDays EMPTY = new LeaveDays();
        BigDecimal paid   = BigDecimal.ZERO;
        BigDecimal unpaid = BigDecimal.ZERO;
    }

    private static final class Structure {
        BigDecimal hoursPerDay;
        BigDecimal overtimeMultiplier;
        String     absenceBasis;
        List<PayrollCalculator.Component> components;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal floorAtZero(BigDecimal v) { return v.signum() < 0 ? BigDecimal.ZERO : v; }
}
