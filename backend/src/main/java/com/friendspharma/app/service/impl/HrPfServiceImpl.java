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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The provident fund — its scheme, its ledger and its balances.
 *
 * <h3>Nothing here decides a rate</h3>
 *
 * <p>P-07 marks the contribution rates BUSINESS / TAX POLICY CONFIRMATION
 * REQUIRED, so a branch ships with <b>no scheme at all</b> and payroll deducts
 * no PF from anybody. A scheme created with no rates on it is a legitimate
 * half-finished state: {@link HrPfScheme#pays()} is false, the screen says so,
 * and payroll still deducts nothing. A null rate is nobody having decided — not
 * nought per cent.
 *
 * <h3>Where the contribution comes from, and when</h3>
 *
 * <p>The payslip figure is computed by {@code PayrollCalculator} when a run is
 * calculated. The <b>ledger</b> row is written when the run is POSTED, because
 * a run can be recalculated any number of times before that and a fund whose
 * balance moved on every recalculation would not be a fund.
 *
 * <p>{@link #recordContributions} is therefore called from
 * {@code HrPayrollServiceImpl.post}, and it is idempotent: a run that already
 * contributed writes nothing the second time, which is what
 * {@code uq_hr_pf_ledger_contribution} enforces underneath.
 *
 * <h3>Authorisation</h3>
 *
 * <p>Gated on <b>Payroll (MOD36)</b> rather than a module of its own. PF is
 * payroll money — it is deducted by a payroll run and it is the same people who
 * run payroll who administer the fund — and inventing a module would mean a new
 * grant migration and a row on the RBAC screen that nobody would think to tick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPfServiceImpl {

    private final HrPfSchemeRepository  schemes;
    private final HrPfLedgerRepository  ledger;
    private final HrEmployeeRepository  employeeRepo;
    private final HrSalaryComponentRepository componentRepo;
    private final HrScope               scope;

    /* ══ The scheme ═════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public HrResponses.PfSchemeDto current() {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see the provident fund");
        Long branch = scope.branchId();
        return schemes.findCurrent(branch).map(this::map).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<HrResponses.PfSchemeDto> history() {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see the provident fund");
        return schemes.findByPidBranchOrderByEffectiveFromDesc(scope.branchId())
                .stream().map(this::map).toList();
    }

    /**
     * Sets the scheme, superseding whatever was in force.
     *
     * <p>Supersession closes the live row rather than editing it: last month's
     * payroll was computed against last month's rates, and a scheme edited in
     * place would make those payslips unexplainable.
     */
    @Transactional
    public HrResponses.PfSchemeDto setScheme(HrRequests.PfScheme req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT, "set the provident fund scheme");

        Long branch = scope.branchId();
        LocalDate from = req.getEffectiveFrom() == null ? LocalDate.now() : req.getEffectiveFrom();
        String base = normaliseBase(req.getContributionBase());

        requirePct(req.getEmployeePct(), "employee");
        requirePct(req.getEmployerPct(), "employer");

        schemes.findCurrent(branch).ifPresent(live -> {
            if (!from.isAfter(live.getEffectiveFrom())) {
                throw new BusinessException(
                        "The current scheme took effect on " + live.getEffectiveFrom()
                        + ". A new one has to start after that — otherwise two schemes would "
                        + "govern the same payslip.",
                        HttpStatus.CONFLICT, "HR_PF_SCHEME_OVERLAP");
            }
            live.setEffectiveTo(from.minusDays(1));
            /* saveAndFlush, not save. uq_hr_pf_scheme_current allows one row per
               branch with a null EFFECTIVE_TO, and Hibernate orders a
               transaction's inserts before its updates — a plain save() would
               insert the new open-ended scheme while the old one was still open
               and the index would refuse it. The same flush-order trap the
               working policy hit. */
            schemes.saveAndFlush(live);
        });

        HrPfScheme s = HrPfScheme.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .schemeName(trim(req.getSchemeName()) == null ? "Provident fund" : trim(req.getSchemeName()))
                .employeePct(req.getEmployeePct())
                .employerPct(req.getEmployerPct())
                .contributionBase(base)
                .wageCeiling(req.getWageCeiling())
                .minServiceMonths(req.getMinServiceMonths())
                .effectiveFrom(from)
                .remarks(req.getRemarks())
                .build();
        s.setStatus("Y");
        schemes.save(s);

        log.info("PF scheme set for branch {}: employee {}%, employer {}%, on {} from {}",
                 branch, req.getEmployeePct(), req.getEmployerPct(), base, from);
        return map(s);
    }

    /* ══ What payroll asks for ══════════════════════════════════════════ */

    /** The scheme that governed a date, or null. Used by the payroll run. */
    @Transactional(readOnly = true)
    public HrPfScheme schemeOn(Long branchId, LocalDate on) {
        return schemes.findOn(branchId, on).stream().findFirst()
                .orElseGet(() -> schemes.findCurrent(branchId).orElse(null));
    }

    /**
     * The terms that apply to one person, or null when none do.
     *
     * <p>Null for three separate reasons, all of which mean "no PF line on this
     * payslip": there is no scheme, the scheme names no rate, or this employee
     * is not in it — either because {@code PF_APPLICABLE} is off or because they
     * have not served the qualifying period yet.
     */
    public PayrollCalculator.Pf termsFor(HrPfScheme scheme, HrEmployee e, LocalDate on) {
        if (scheme == null || !scheme.pays()) return null;
        if (e == null || e.getPfApplicable() == null || e.getPfApplicable() != 1) return null;
        if (!servedLongEnough(scheme, e, on)) return null;

        return new PayrollCalculator.Pf(
                scheme.getEmployeePct(), scheme.getEmployerPct(),
                scheme.getContributionBase(), scheme.getWageCeiling());
    }

    /**
     * Whether somebody has been employed long enough to contribute.
     *
     * <p>An employee with no hire date on record is treated as eligible: the
     * date is optional on the employee form, and refusing to enrol somebody
     * because a field was left blank would be the application making a
     * membership decision on a technicality.
     */
    public boolean servedLongEnough(HrPfScheme scheme, HrEmployee e, LocalDate on) {
        Integer required = scheme.getMinServiceMonths();
        if (required == null || required <= 0) return true;
        if (e.getHireDate() == null || on == null) return true;
        long months = ChronoUnit.MONTHS.between(e.getHireDate(), on);
        return months >= required;
    }

    /* ══ The ledger ═════════════════════════════════════════════════════ */

    /**
     * Writes one contribution row per payslip that carried PF.
     *
     * <p>Called when a payroll run is posted. Idempotent by design — a run that
     * already contributed writes nothing — because posting is a button and a
     * fund that could be credited twice by a double click is not a fund.
     *
     * @return how many rows were written
     */
    @Transactional
    public int recordContributions(HrPayroll run, HrPayrollPeriod period, Long schemeId) {
        if (ledger.countByPidPayrollAndEntryTypeAndStatus(
                run.getPidPayroll(), HrPfLedger.CONTRIBUTION, "Y") > 0) {
            log.info("Payroll {} has already contributed to the fund; nothing written",
                     run.getPayrollNo());
            return 0;
        }

        HrPfScheme scheme = schemeId == null ? null : schemes.findById(schemeId).orElse(null);
        LocalDate when = period == null ? LocalDate.now() : period.getPeriodTo();

        int written = 0;
        for (HrPayrollLine line : run.getLines()) {
            BigDecimal employee = nz(line.getPfEmployeeAmount());
            BigDecimal employer = nz(line.getPfEmployerAmount());
            if (employee.signum() <= 0 && employer.signum() <= 0) continue;

            HrPfLedger row = HrPfLedger.builder()
                    .pidOrg(run.getPidOrg()).pidCompany(run.getPidCompany())
                    .pidBranch(run.getPidBranch())
                    .pidEmployee(line.getPidEmployee())
                    .pidPayroll(run.getPidPayroll())
                    .pidPayrollLine(line.getPidPayrollLine())
                    .pidPfScheme(schemeId)
                    .entryDate(when)
                    .periodFrom(period == null ? null : period.getPeriodFrom())
                    .periodTo(period == null ? null : period.getPeriodTo())
                    .entryType(HrPfLedger.CONTRIBUTION)
                    .baseAmount(pfBaseOf(scheme, line))
                    .employeePct(scheme == null ? null : scheme.getEmployeePct())
                    .employerPct(scheme == null ? null : scheme.getEmployerPct())
                    .employeeAmount(employee)
                    .employerAmount(employer)
                    .remarks("Payroll " + run.getPayrollNo())
                    .recordedBy(scope.actor())
                    .recordedAt(LocalDateTime.now())
                    .build();
            row.setStatus("Y");
            ledger.save(row);
            written++;
        }

        log.info("Payroll {} posted {} provident fund contributions", run.getPayrollNo(), written);
        return written;
    }

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.PfLedgerDto> search(Long employeeId, String entryType,
                                                        LocalDate from, LocalDate to,
                                                        Pageable pageable) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see the provident fund");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                ledger.search(scope.branchId(), employeeId,
                              entryType == null ? "" : entryType.trim().toUpperCase(),
                              from, to, pageable)
                        .map(l -> map(l, staff.get(l.getPidEmployee()))));
    }

    /** One person's statement: every movement, with the balance after each. */
    @Transactional(readOnly = true)
    public HrResponses.PfStatementDto statement(Long employeeId) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see the provident fund");
        HrEmployee e = scope.employee(employeeId);

        List<HrPfLedger> rows = ledger.findByPidEmployeeAndStatusOrderByEntryDateAscPidPfLedgerAsc(
                e.getPidEmployee(), "Y");

        List<HrResponses.PfLedgerDto> lines = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal employeeTotal = BigDecimal.ZERO, employerTotal = BigDecimal.ZERO;
        for (HrPfLedger r : rows) {
            running = running.add(r.signedTotal());
            employeeTotal = employeeTotal.add(r.signedEmployee());
            employerTotal = employerTotal.add(r.signedEmployer());
            HrResponses.PfLedgerDto dto = map(r, e);
            dto.setBalanceAfter(running);
            lines.add(dto);
        }

        HrPfScheme scheme = schemes.findCurrent(e.getPidBranch()).orElse(null);
        return HrResponses.PfStatementDto.builder()
                .employeeId(e.getPidEmployee())
                .empId(e.getEmpId())
                .empName(e.getEmpName())
                .enrolled(e.getPfApplicable() != null && e.getPfApplicable() == 1)
                .schemeConfigured(scheme != null && scheme.pays())
                .employeeTotal(employeeTotal)
                .employerTotal(employerTotal)
                .balance(running)
                .entries(lines)
                .build();
    }

    /**
     * Records a movement somebody makes by hand.
     *
     * <p>A contribution is not one of them — that comes from posting payroll,
     * and letting it be typed would put a figure in the fund that no payslip
     * accounts for.
     */
    @Transactional
    public HrResponses.PfLedgerDto record(HrRequests.PfEntry req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.APPROVE,
                      "record a provident fund movement");

        HrEmployee e = scope.employee(req.getEmployeeId());
        String type = req.getEntryType() == null ? "" : req.getEntryType().trim().toUpperCase();
        if (!HrPfLedger.MANUAL_TYPES.contains(type)) {
            throw new BusinessException(
                    "A contribution is written by posting payroll, not entered by hand. "
                    + "This can be " + String.join(", ", HrPfLedger.MANUAL_TYPES) + ".",
                    HttpStatus.BAD_REQUEST, "HR_PF_ENTRY_TYPE_INVALID");
        }

        BigDecimal employee = nz(req.getEmployeeAmount());
        BigDecimal employer = nz(req.getEmployerAmount());
        if (employee.signum() <= 0 && employer.signum() <= 0) {
            throw new BusinessException("A movement of nothing is not a movement.",
                    HttpStatus.BAD_REQUEST, "HR_PF_ZERO_ENTRY");
        }

        /* A fund cannot pay out what it does not hold. The balance is read here
           rather than trusted from the caller for the obvious reason. */
        if (HrPfLedger.WITHDRAWAL.equals(type) || HrPfLedger.SETTLEMENT.equals(type)) {
            BigDecimal held = nz(ledger.balanceOf(e.getPidEmployee()));
            BigDecimal wanted = employee.add(employer);
            if (wanted.compareTo(held) > 0) {
                throw new BusinessException(
                        e.getEmpName() + " has " + held + " in the fund and this takes out "
                        + wanted + ". A fund cannot pay out what it does not hold.",
                        HttpStatus.CONFLICT, "HR_PF_INSUFFICIENT_BALANCE");
            }
        }

        HrPfLedger row = HrPfLedger.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .entryDate(req.getEntryDate() == null ? LocalDate.now() : req.getEntryDate())
                .entryType(type)
                .employeeAmount(employee)
                .employerAmount(employer)
                .referenceNo(trim(req.getReferenceNo()))
                .remarks(req.getRemarks())
                .recordedBy(scope.actor())
                .recordedAt(LocalDateTime.now())
                .build();
        row.setStatus("Y");
        ledger.save(row);

        log.info("PF {} for employee {}: {} + {}", type, e.getPidEmployee(), employee, employer);
        return map(row, e);
    }

    /* ══ Mapping and helpers ════════════════════════════════════════════ */

    /**
     * Deduction components that a scheme would silently override.
     *
     * <p>Advisory only, and matched by name, which is exactly why it is advisory:
     * this is a prompt for a person to tick the right box, never an inference the
     * calculator acts on. A component only counts as PF once somebody marks it.
     */
    private List<String> unmarkedPfComponents(Long branchId) {
        List<String> out = new ArrayList<>();
        for (HrSalaryComponent c : componentRepo.findByBranch(branchId)) {
            if (!PayrollCalculator.DEDUCTION.equalsIgnoreCase(c.getComponentType())) continue;
            if (c.getIsPf() != null && c.getIsPf() == 1) continue;
            String name = (c.getComponentName() + " " + c.getComponentCode()).toLowerCase(Locale.ROOT);
            if (name.contains("provident") || name.contains("pf") || name.contains("epf")) {
                out.add(c.getComponentName());
            }
        }
        return out;
    }

    private static BigDecimal pfBaseOf(HrPfScheme scheme, HrPayrollLine line) {
        if (scheme == null) return null;
        return HrPfScheme.ON_GROSS.equals(scheme.getContributionBase())
                ? line.getGrossSalary() : line.getBasicSalary();
    }

    private static String normaliseBase(String raw) {
        String v = raw == null || raw.isBlank() ? HrPfScheme.ON_BASIC : raw.trim().toUpperCase();
        if (!HrPfScheme.BASES.contains(v)) {
            throw new BusinessException(
                    "A contribution is taken on the basic or on the gross.",
                    HttpStatus.BAD_REQUEST, "HR_PF_BASE_INVALID");
        }
        return v;
    }

    private static void requirePct(BigDecimal pct, String whose) {
        if (pct == null) return;   // null is "not decided", which is allowed
        if (pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(
                    "The " + whose + " share has to be between 0 and 100 per cent.",
                    HttpStatus.BAD_REQUEST, "HR_PF_PCT_INVALID");
        }
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.PfSchemeDto map(HrPfScheme s) {
        return HrResponses.PfSchemeDto.builder()
                .id(s.getPidPfScheme())
                .schemeName(s.getSchemeName())
                .employeePct(s.getEmployeePct())
                .employerPct(s.getEmployerPct())
                .contributionBase(s.getContributionBase())
                .wageCeiling(s.getWageCeiling())
                .minServiceMonths(s.getMinServiceMonths())
                .effectiveFrom(s.getEffectiveFrom())
                .effectiveTo(s.getEffectiveTo())
                .current(s.isCurrent())
                .rateSet(s.isRateSet())
                .deducting(s.pays())
                .remarks(s.getRemarks())
                .unmarkedPfComponents(s.isCurrent() ? unmarkedPfComponents(s.getPidBranch()) : List.of())
                .build();
    }

    HrResponses.PfLedgerDto map(HrPfLedger l, HrEmployee e) {
        return HrResponses.PfLedgerDto.builder()
                .id(l.getPidPfLedger())
                .employeeId(l.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .entryDate(l.getEntryDate())
                .periodFrom(l.getPeriodFrom())
                .periodTo(l.getPeriodTo())
                .entryType(l.getEntryType())
                .entryTypeLabel(HrConstants.label(l.getEntryType()))
                .baseAmount(l.getBaseAmount())
                .employeePct(l.getEmployeePct())
                .employerPct(l.getEmployerPct())
                .employeeAmount(l.getEmployeeAmount())
                .employerAmount(l.getEmployerAmount())
                .signedTotal(l.signedTotal())
                .payrollId(l.getPidPayroll())
                .referenceNo(l.getReferenceNo())
                .remarks(l.getRemarks())
                .recordedBy(l.getRecordedBy())
                .recordedAt(l.getRecordedAt())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
