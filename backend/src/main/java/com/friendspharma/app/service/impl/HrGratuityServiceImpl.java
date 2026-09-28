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
 * Gratuity — the formula, the estimate, and the award.
 *
 * <h3>Nothing here decides a formula</h3>
 *
 * <p>P-08 marks days per year, the wage basis, the monthly divisor, the
 * qualifying period and the part-year rule BUSINESS POLICY CONFIRMATION
 * REQUIRED. So a branch ships with <b>no policy</b>, and with none, nothing is
 * computed for anybody — {@link #estimate} answers "not configured" rather than
 * a number, and an award cannot be created at all.
 *
 * <p>A policy row with blanks in it is a legitimate half-finished state.
 * {@link HrGratuityPolicy#isComplete()} is false, and the calculation still
 * refuses rather than reading the blanks as zero.
 *
 * <h3>An award is frozen, not accrued</h3>
 *
 * <p>Service years, the wage, the daily rate, the days and the amount are
 * copied onto the award and never recomputed — the same rule
 * {@link HrIncrement} follows, and for the same reason: a figure recalculated
 * later is a decision changed after it was made.
 *
 * <p>No monthly accrual is posted anywhere. That would be an accounting policy
 * as well as a gratuity one, and there is no Accounts module in this build to
 * post it to.
 *
 * <h3>What it does not do to a separation</h3>
 *
 * <p>{@code SETTLEMENT_AMOUNT} is untouched: P-05 says a final settlement is
 * entered, never computed, and that is still unanswered. An approved award is
 * shown beside the separation so whoever settles it can see the figure and
 * decide.
 *
 * <h3>Authorisation</h3>
 *
 * <p>Gated on <b>Employees (MOD32)</b>, the module separations already sit
 * under, rather than a module of its own — gratuity is part of somebody
 * leaving, and a new module would mean a grant migration and a row on the RBAC
 * screen nobody would think to tick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrGratuityServiceImpl {

    private final HrGratuityPolicyRepository policies;
    private final HrGratuityAwardRepository  awards;
    private final HrEmployeeRepository       employeeRepo;
    private final HrSeparationRepository     separations;
    private final HrPayrollLineRepository    payrollLines;
    private final HrApprovalService          approvals;
    private final HrScope                    scope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /* ══ The policy ═════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public HrResponses.GratuityPolicyDto current() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see the gratuity policy");
        return policies.findCurrent(scope.branchId()).map(this::map).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<HrResponses.GratuityPolicyDto> history() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see the gratuity policy");
        return policies.findByPidBranchOrderByEffectiveFromDesc(scope.branchId())
                .stream().map(this::map).toList();
    }

    @Transactional
    public HrResponses.GratuityPolicyDto setPolicy(HrRequests.GratuityPolicy req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT, "set the gratuity policy");

        Long branch = scope.branchId();
        LocalDate from = req.getEffectiveFrom() == null ? LocalDate.now() : req.getEffectiveFrom();
        String basis = normaliseBasis(req.getWageBasis());
        String rule  = normaliseRule(req.getPartialYearRule());

        if (req.getMinServiceYears() != null && req.getMaxServiceYears() != null
                && req.getMaxServiceYears().compareTo(req.getMinServiceYears()) < 0) {
            throw new BusinessException(
                    "The service cap cannot be below the qualifying period — nobody would ever "
                    + "qualify.",
                    HttpStatus.BAD_REQUEST, "HR_GRATUITY_RANGE_INVALID");
        }

        policies.findCurrent(branch).ifPresent(live -> {
            if (!from.isAfter(live.getEffectiveFrom())) {
                throw new BusinessException(
                        "The current policy took effect on " + live.getEffectiveFrom()
                        + ". A new one has to start after that — otherwise two policies would "
                        + "govern the same award.",
                        HttpStatus.CONFLICT, "HR_GRATUITY_POLICY_OVERLAP");
            }
            live.setEffectiveTo(from.minusDays(1));
            /* saveAndFlush: the partial unique index allows one open-ended row
               per branch, and Hibernate flushes inserts before updates. The
               same trap the working policy and the PF scheme both hit. */
            policies.saveAndFlush(live);
        });

        HrGratuityPolicy p = HrGratuityPolicy.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .policyName(trim(req.getPolicyName()) == null ? "Gratuity" : trim(req.getPolicyName()))
                .daysPerYear(req.getDaysPerYear())
                .daysInMonth(req.getDaysInMonth())
                .wageBasis(basis)
                .minServiceYears(req.getMinServiceYears())
                .maxServiceYears(req.getMaxServiceYears())
                .partialYearRule(rule)
                .effectiveFrom(from)
                .remarks(req.getRemarks())
                .build();
        p.setStatus("Y");
        policies.save(p);

        log.info("Gratuity policy set for branch {}: {} days per year on {} / {} days a month, {}",
                 branch, req.getDaysPerYear(), basis, req.getDaysInMonth(), rule);
        return map(p);
    }

    /* ══ The estimate ═══════════════════════════════════════════════════ */

    /**
     * What somebody would be owed, without writing anything.
     *
     * <p>Answers "not configured" rather than a figure when the branch has no
     * complete policy. That distinction matters: nought is an answer, and this
     * is not one.
     */
    @Transactional(readOnly = true)
    public HrResponses.GratuityEstimateDto estimate(Long employeeId, LocalDate asOf) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see a gratuity estimate");

        HrEmployee e = scope.employee(employeeId);
        LocalDate to = asOf == null ? LocalDate.now() : asOf;
        HrGratuityPolicy p = policyOn(e.getPidBranch(), to);

        return compute(e, p, to, null);
    }

    /* ══ The award ══════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.GratuityAwardDto> search(Long employeeId, String status,
                                                             Pageable pageable) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see gratuity awards");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                awards.search(scope.branchId(), employeeId,
                              status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(a -> map(a, staff.get(a.getPidEmployee()))));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.GratuityAwardDto> pending() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see gratuity awards");
        Map<Long, HrEmployee> staff = staffById();
        return awards.findByPidBranchAndAwardStatusAndStatusOrderByServiceTo(
                        scope.branchId(), HrGratuityAward.SUBMITTED, "Y")
                .stream().map(a -> map(a, staff.get(a.getPidEmployee()))).toList();
    }

    /**
     * Creates an award from the policy in force on the service end date.
     *
     * <p>The figures are computed here from the policy and the salary as they
     * stand today, and shown on a draft. They are re-read at approval, which is
     * where they freeze — see {@link #approve}.
     */
    @Transactional
    public HrResponses.GratuityAwardDto create(HrRequests.GratuityAward req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.CREATE, "award gratuity");

        HrEmployee e = scope.employee(req.getEmployeeId());
        HrSeparation sep = null;
        if (req.getSeparationId() != null) {
            sep = separations.findByPidSeparationAndPidBranch(req.getSeparationId(), scope.branchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Separation", req.getSeparationId()));
            if (!Objects.equals(sep.getPidEmployee(), e.getPidEmployee())) {
                throw new BusinessException(
                        "That separation belongs to somebody else.",
                        HttpStatus.BAD_REQUEST, "HR_GRATUITY_SEPARATION_MISMATCH");
            }
        }

        LocalDate serviceTo = req.getServiceTo() != null ? req.getServiceTo()
                : sep != null ? sep.getLastWorkingDate()
                : LocalDate.now();
        LocalDate serviceFrom = req.getServiceFrom() != null ? req.getServiceFrom() : e.getHireDate();

        if (serviceFrom == null) {
            throw new BusinessException(
                    e.getEmpName() + " has no hire date on record, so their service cannot be "
                    + "measured. Set it on the employee, or give a service start here.",
                    HttpStatus.BAD_REQUEST, "HR_GRATUITY_NO_SERVICE_START");
        }

        HrGratuityPolicy p = policyOn(e.getPidBranch(), serviceTo);
        HrResponses.GratuityEstimateDto worked = compute(e, p, serviceTo, serviceFrom);

        if (!worked.isConfigured()) {
            throw new BusinessException(
                    "This branch has no gratuity policy, so there is nothing to award from. "
                    + "Days per year and the days-in-a-month divisor are business policy and "
                    + "this application will not invent them — set them first.",
                    HttpStatus.CONFLICT, "HR_GRATUITY_NOT_CONFIGURED");
        }
        if (!worked.isQualified()) {
            throw new BusinessException(
                    e.getEmpName() + " has " + worked.getRawYears() + " years of service and the "
                    + "policy requires " + worked.getMinServiceYears() + ".",
                    HttpStatus.CONFLICT, "HR_GRATUITY_NOT_QUALIFIED");
        }
        if (!awards.findLiveFor(e.getPidEmployee(), serviceTo, null).isEmpty()) {
            throw new BusinessException(
                    e.getEmpName() + " already has a gratuity award for service to " + serviceTo + ".",
                    HttpStatus.CONFLICT, "HR_GRATUITY_ALREADY_AWARDED");
        }

        HrGratuityAward a = HrGratuityAward.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .pidSeparation(sep == null ? null : sep.getPidSeparation())
                .pidGratuityPolicy(p.getPidGratuityPolicy())
                .serviceFrom(serviceFrom)
                .serviceTo(serviceTo)
                .serviceYears(worked.getCountableYears())
                .wageBasis(worked.getWageBasis())
                .monthlyWage(worked.getMonthlyWage())
                .daysInMonth(worked.getDaysInMonth())
                .dailyWage(worked.getDailyWage())
                .daysPerYear(worked.getDaysPerYear())
                .daysAwarded(worked.getDaysAwarded())
                .gratuityAmount(worked.getGratuityAmount())
                .reason(req.getReason())
                .awardStatus(HrGratuityAward.DRAFT)
                .build();
        a.setStatus("Y");
        awards.save(a);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(a);
        return map(a, e);
    }

    @Transactional
    public HrResponses.GratuityAwardDto submit(Long id) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.CREATE, "submit a gratuity award");
        HrGratuityAward a = award(id);
        submitInternal(a);
        return map(a, scope.employee(a.getPidEmployee()));
    }

    private void submitInternal(HrGratuityAward a) {
        if (!a.isDraft()) {
            throw new BusinessException("This award has already been submitted.",
                    HttpStatus.CONFLICT, "HR_GRATUITY_ALREADY_SUBMITTED");
        }
        a.setAwardStatus(HrGratuityAward.SUBMITTED);
        a.setSubmittedBy(scope.actor());
        a.setSubmittedAt(LocalDateTime.now());
        awards.saveAndFlush(a);

        /* Routed on the amount. A leaving payment is usually the largest single
           figure in HR, which is exactly the kind a pharmacy wants two
           signatures on. */
        approvals.begin(HrScope.WF_GRATUITY, a.getPidGratuityAward(),
                        scope.employee(a.getPidEmployee()), a.getGratuityAmount());
    }

    /**
     * Approves, freezing the working.
     *
     * <p>The figures are recomputed once here, from the policy and the salary as
     * they stand at the moment of the decision, and then never again. A draft
     * created last month against a salary that has since changed is approved at
     * today's figures, and the row records what today's figures were.
     */
    @Transactional
    public HrResponses.GratuityAwardDto approve(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "approve gratuity");

        HrGratuityAward a = award(id);
        requireSubmitted(a);
        HrEmployee e = scope.employee(a.getPidEmployee());

        /* Not necessarily the last signature. The working is not frozen until
           it is — see the class comment on why an award's figures move once and
           then never again. */
        if (approvals.decide(HrScope.WF_GRATUITY, id, true, d == null ? null : d.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(a, e);
        }

        HrGratuityPolicy p = policyOn(e.getPidBranch(), a.getServiceTo());
        HrResponses.GratuityEstimateDto worked = compute(e, p, a.getServiceTo(), a.getServiceFrom());
        if (!worked.isConfigured()) {
            throw new BusinessException(
                    "The gratuity policy that governed " + a.getServiceTo()
                    + " is no longer complete, so this award cannot be signed off against it.",
                    HttpStatus.CONFLICT, "HR_GRATUITY_NOT_CONFIGURED");
        }

        a.setPidGratuityPolicy(p.getPidGratuityPolicy());
        a.setServiceYears(worked.getCountableYears());
        a.setWageBasis(worked.getWageBasis());
        a.setMonthlyWage(worked.getMonthlyWage());
        a.setDaysInMonth(worked.getDaysInMonth());
        a.setDailyWage(worked.getDailyWage());
        a.setDaysPerYear(worked.getDaysPerYear());
        a.setDaysAwarded(worked.getDaysAwarded());
        a.setGratuityAmount(worked.getGratuityAmount());

        a.setAwardStatus(HrGratuityAward.APPROVED);
        a.setDecidedBy(scope.actor());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionRemarks(d == null ? null : d.getRemarks());
        awards.save(a);

        log.info("Gratuity approved for employee {}: {} years, {} days, {}",
                 e.getPidEmployee(), a.getServiceYears(), a.getDaysAwarded(), a.getGratuityAmount());
        return map(a, e);
    }

    @Transactional
    public HrResponses.GratuityAwardDto reject(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "reject gratuity");
        HrGratuityAward a = award(id);
        requireSubmitted(a);
        approvals.decide(HrScope.WF_GRATUITY, id, false, d == null ? null : d.getRemarks());
        a.setAwardStatus(HrGratuityAward.REJECTED);
        a.setDecidedBy(scope.actor());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionRemarks(d == null ? null : d.getRemarks());
        return map(awards.save(a), scope.employee(a.getPidEmployee()));
    }

    /** Records the payment. Nothing is recomputed — the amount was settled at approval. */
    @Transactional
    public HrResponses.GratuityAwardDto pay(Long id, HrRequests.GratuityPayment req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "pay gratuity");

        HrGratuityAward a = award(id);
        if (!a.isApproved()) {
            throw new BusinessException(
                    "Only an approved award can be paid. This one is "
                    + HrConstants.label(a.getAwardStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_GRATUITY_NOT_APPROVED");
        }
        String mode = req == null || req.getPaymentMode() == null
                ? null : req.getPaymentMode().trim().toUpperCase();
        if (mode == null || !HrConstants.PaymentMode.ALL.contains(mode)) {
            throw new BusinessException(
                    "How was it paid? Expected one of " + HrConstants.PaymentMode.ALL + ".",
                    HttpStatus.BAD_REQUEST, "HR_PAYMENT_MODE_INVALID");
        }

        a.setAwardStatus(HrGratuityAward.PAID);
        a.setPaidDate(req.getPaidDate() == null ? LocalDate.now() : req.getPaidDate());
        a.setPaymentMode(mode);
        a.setPaymentReference(trim(req.getPaymentReference()));
        a.setPaidBy(scope.actor());
        HrGratuityAward paid = awards.save(a);
        accounting.gratuityPaid(paid);
        return map(paid, scope.employee(a.getPidEmployee()));
    }

    /** A paid award is a record of a payment and is not cancellable. */
    @Transactional
    public HrResponses.GratuityAwardDto cancel(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.DELETE, "cancel a gratuity award");

        HrGratuityAward a = award(id);
        if (a.isPaid()) {
            throw new BusinessException(
                    "This award has been paid. Cancelling it would not take the money back — "
                    + "record what actually happened instead.",
                    HttpStatus.CONFLICT, "HR_GRATUITY_PAID");
        }
        if (HrGratuityAward.CANCELLED.equals(a.getAwardStatus())
                || HrGratuityAward.REJECTED.equals(a.getAwardStatus())) {
            throw new BusinessException("This award is already closed.",
                    HttpStatus.CONFLICT, "HR_GRATUITY_ALREADY_CLOSED");
        }

        approvals.cancelFor(HrScope.WF_GRATUITY, id);
        a.setAwardStatus(HrGratuityAward.CANCELLED);
        a.setDecidedBy(scope.actor());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionRemarks(d == null ? null : d.getRemarks());
        return map(awards.save(a), scope.employee(a.getPidEmployee()));
    }

    /* ══ The arithmetic ═════════════════════════════════════════════════ */

    /**
     * The whole working, whether or not it is ever saved.
     *
     * <p>One method for the estimate, the draft and the approval, so the three
     * cannot drift apart. The policy itself owns the rules — see
     * {@link HrGratuityPolicy}.
     */
    private HrResponses.GratuityEstimateDto compute(HrEmployee e, HrGratuityPolicy p,
                                                     LocalDate serviceTo, LocalDate overrideFrom) {
        LocalDate from = overrideFrom != null ? overrideFrom : e.getHireDate();

        HrResponses.GratuityEstimateDto.GratuityEstimateDtoBuilder out =
                HrResponses.GratuityEstimateDto.builder()
                        .employeeId(e.getPidEmployee())
                        .empId(e.getEmpId())
                        .empName(e.getEmpName())
                        .entitled(e.getGratuityApplicable() != null && e.getGratuityApplicable() == 1)
                        .serviceFrom(from)
                        .serviceTo(serviceTo);

        if (p == null || !p.isComplete()) {
            return out.configured(false)
                    .unansweredPolicy(
                            "Days of pay per year of service and the days-in-a-month divisor are "
                            + "business policy — 26 working days and 30 calendar days give "
                            + "materially different answers on the same salary. Neither is set at "
                            + "this branch, and this application will not choose them.")
                    .build();
        }

        BigDecimal raw = HrGratuityPolicy.rawYears(from, serviceTo);
        BigDecimal countable = p.countableYears(raw);
        boolean qualified = p.qualifies(raw);

        BigDecimal wage = HrGratuityPolicy.ON_GROSS.equals(p.getWageBasis())
                ? lastGross(e) : nz(e.getBasicSalary());
        BigDecimal daily = p.dailyWage(wage);
        BigDecimal days  = qualified
                ? countable.multiply(p.getDaysPerYear()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal amount = daily.multiply(days).setScale(2, RoundingMode.HALF_UP);

        return out.configured(true)
                .policyId(p.getPidGratuityPolicy())
                .policyName(p.getPolicyName())
                .qualified(qualified)
                .rawYears(raw)
                .countableYears(countable)
                .minServiceYears(p.getMinServiceYears())
                .maxServiceYears(p.getMaxServiceYears())
                .partialYearRule(p.getPartialYearRule())
                .wageBasis(p.getWageBasis())
                .monthlyWage(wage)
                .daysInMonth(p.getDaysInMonth())
                .dailyWage(daily)
                .daysPerYear(p.getDaysPerYear())
                .daysAwarded(days)
                .gratuityAmount(amount)
                .wageNote(HrGratuityPolicy.ON_GROSS.equals(p.getWageBasis())
                        ? "Gross from the most recent posted payslip; the basic salary where there is none."
                        : "The employee's current basic salary.")
                .build();
    }

    /**
     * The gross a GROSS-basis policy values a day at.
     *
     * <p>Read from the most recent <b>posted</b> payslip rather than recomputed
     * from the structure: a posted payslip is a figure somebody signed, and
     * re-deriving the gross here would be a second implementation of the payroll
     * arithmetic that disagrees with the first the day a structure changes.
     *
     * <p>Falls back to the basic salary when somebody has never been paid
     * through this application, which is honest — it is the only wage on record.
     */
    private BigDecimal lastGross(HrEmployee e) {
        return payrollLines.findLastPostedGross(e.getPidEmployee())
                .stream().findFirst()
                .filter(g -> g != null && g.signum() > 0)
                .orElseGet(() -> nz(e.getBasicSalary()));
    }

    private HrGratuityPolicy policyOn(Long branchId, LocalDate on) {
        return policies.findOn(branchId, on).stream().findFirst()
                .orElseGet(() -> policies.findCurrent(branchId).orElse(null));
    }

    /* ══ Guards, mapping, helpers ═══════════════════════════════════════ */

    private static void requireSubmitted(HrGratuityAward a) {
        if (!a.isSubmitted()) {
            throw new BusinessException(
                    "Only a submitted award can be decided. This one is "
                    + HrConstants.label(a.getAwardStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_GRATUITY_NOT_SUBMITTED");
        }
    }

    private static String normaliseBasis(String raw) {
        String v = raw == null || raw.isBlank() ? HrGratuityPolicy.ON_BASIC : raw.trim().toUpperCase();
        if (!HrGratuityPolicy.BASES.contains(v)) {
            throw new BusinessException("Gratuity is valued on the basic or on the gross.",
                    HttpStatus.BAD_REQUEST, "HR_GRATUITY_BASIS_INVALID");
        }
        return v;
    }

    private static String normaliseRule(String raw) {
        String v = raw == null || raw.isBlank() ? HrGratuityPolicy.IGNORE : raw.trim().toUpperCase();
        if (!HrGratuityPolicy.PARTIAL_RULES.contains(v)) {
            throw new BusinessException(
                    "A part year is ignored, counted pro rata, or rounded up. Expected one of "
                    + HrGratuityPolicy.PARTIAL_RULES + ".",
                    HttpStatus.BAD_REQUEST, "HR_GRATUITY_RULE_INVALID");
        }
        return v;
    }

    private HrGratuityAward award(Long id) {
        return awards.findByPidGratuityAwardAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Gratuity award", id));
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.GratuityPolicyDto map(HrGratuityPolicy p) {
        return HrResponses.GratuityPolicyDto.builder()
                .id(p.getPidGratuityPolicy())
                .policyName(p.getPolicyName())
                .daysPerYear(p.getDaysPerYear())
                .daysInMonth(p.getDaysInMonth())
                .wageBasis(p.getWageBasis())
                .minServiceYears(p.getMinServiceYears())
                .maxServiceYears(p.getMaxServiceYears())
                .partialYearRule(p.getPartialYearRule())
                .partialYearRuleLabel(HrConstants.label(p.getPartialYearRule()))
                .effectiveFrom(p.getEffectiveFrom())
                .effectiveTo(p.getEffectiveTo())
                .current(p.isCurrent())
                .complete(p.isComplete())
                .remarks(p.getRemarks())
                .build();
    }

    HrResponses.GratuityAwardDto map(HrGratuityAward a, HrEmployee e) {
        return HrResponses.GratuityAwardDto.builder()
                .id(a.getPidGratuityAward())
                .awardNo(a.getAwardNo())
                .employeeId(a.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .separationId(a.getPidSeparation())
                .serviceFrom(a.getServiceFrom())
                .serviceTo(a.getServiceTo())
                .serviceYears(a.getServiceYears())
                .wageBasis(a.getWageBasis())
                .monthlyWage(a.getMonthlyWage())
                .daysInMonth(a.getDaysInMonth())
                .dailyWage(a.getDailyWage())
                .daysPerYear(a.getDaysPerYear())
                .daysAwarded(a.getDaysAwarded())
                .gratuityAmount(a.getGratuityAmount())
                .awardStatus(a.getAwardStatus())
                .awardStatusLabel(HrConstants.label(a.getAwardStatus()))
                .submittedBy(a.getSubmittedBy())
                .submittedAt(a.getSubmittedAt())
                .decidedBy(a.getDecidedBy())
                .decidedAt(a.getDecidedAt())
                .decisionRemarks(a.getDecisionRemarks())
                .paidDate(a.getPaidDate())
                .paymentMode(a.getPaymentMode())
                .paymentReference(a.getPaymentReference())
                .reason(a.getReason())
                .editable(a.isDraft())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
