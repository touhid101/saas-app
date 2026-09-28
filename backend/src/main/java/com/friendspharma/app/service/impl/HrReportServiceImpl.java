package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.ReportGridResponse;
import com.friendspharma.app.dto.response.ReportGridResponse.Column;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.HrReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.friendspharma.app.service.impl.ReportGridServiceImpl.bd;
import static com.friendspharma.app.service.impl.ReportGridServiceImpl.col;
import static com.friendspharma.app.service.impl.ReportGridServiceImpl.grid;
import static com.friendspharma.app.service.impl.ReportGridServiceImpl.money;
import static com.friendspharma.app.service.impl.ReportGridServiceImpl.num;
import static com.friendspharma.app.service.impl.ReportGridServiceImpl.row;

/**
 * The HR half of the Reports screen.
 *
 * <p>A separate class from {@link ReportGridServiceImpl} because that one is
 * already six hundred lines and nineteen more reports would make it
 * unreadable — but the <em>same</em> grid contract, the same column helpers and
 * the same endpoint. There is one report engine here, not two: the catalogue
 * merges, {@code /api/reports/{id}} dispatches to whichever service owns the id,
 * and {@code Reports.jsx} needs no change to render any of these.
 *
 * <h3>Payroll figures are read, never recomputed</h3>
 *
 * <p>Gross, deductions and net come out of {@code HR_PAYROLL_LINE} exactly as
 * {@code PayrollCalculator} wrote them. A report that re-derives a payslip is a
 * second implementation of the payroll arithmetic, and the first time the two
 * disagree nobody can tell which is right.
 *
 * <h3>Minutes become hours here, once</h3>
 *
 * <p>Attendance stores minutes — hours as a decimal loses a minute to rounding
 * on almost every row. The reports show hours because that is what a manager
 * reads, and the conversion happens in one place so two reports cannot round
 * the same figure differently.
 *
 * <h3>What is not here, and why</h3>
 *
 * <p>PF, gratuity, increment, expense and HR audit reports are absent because
 * the tables behind them do not exist yet. Registering an empty report would put
 * a name in the catalogue that returns nothing, which reads as a broken report
 * rather than as a module that has not been built.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrReportServiceImpl {

    private final HrReportRepository repo;
    private final HrScope scope;

    /** Every id here is prefixed {@code hr-}, which is how dispatch routes them. */
    public static final Map<String, String> CATALOGUE = new LinkedHashMap<>() {{
        put("hr-employees",           "Employee Register");
        put("hr-headcount",           "Headcount");
        put("hr-separation",          "Employee Separation");
        put("hr-attendance",          "Attendance Register");
        put("hr-attendance-summary",  "Monthly Attendance Summary");
        put("hr-late",                "Late Arrivals");
        put("hr-absent",              "Absences");
        put("hr-overtime",            "Overtime");
        put("hr-overtime-claims",     "Overtime Claims");
        put("hr-leave-register",      "Leave Register");
        put("hr-leave-balance",       "Leave Balance");
        put("hr-leave-utilisation",   "Leave Utilisation");
        put("hr-leave-pending",       "Leave Awaiting Approval");
        put("hr-salary-register",     "Salary Register");
        put("hr-increment",           "Increment Register");
        put("hr-pf",                  "Provident Fund");
        put("hr-gratuity",            "Gratuity Awards");
        put("hr-expense",             "Expense & Conveyance");
        put("hr-advance",             "Advance Register");
        put("hr-loan",                "Loan Register");
        put("hr-payroll-register",    "Payroll Register");
        put("hr-payroll-summary",     "Payroll Summary");
        put("hr-payroll-cost",        "Payroll Cost by Department");
        put("hr-payroll-deduction",   "Payroll Deductions");
        put("hr-payroll-exception",   "Payroll Exceptions");
    }};

    public static boolean owns(String id) {
        return id != null && CATALOGUE.containsKey(id);
    }

    @Transactional(readOnly = true)
    public ReportGridResponse run(String id, Long orgId, ReportGridServiceImpl.Filters f) {
        /* V87: an HR report needs View on HR Reports, whatever the caller holds
           on the general Reports screen. Payroll figures are not sales figures. */
        scope.require(HrScope.MOD_HR_REPORTS, PermissionGuard.Right.VIEW, "run an HR report");
        return switch (id) {
            case "hr-employees"          -> employees(orgId, f);
            case "hr-headcount"          -> headcount(orgId, f);
            case "hr-separation"         -> separations(orgId, f);
            case "hr-attendance"         -> attendance(orgId, f, "");
            case "hr-late"               -> attendance(orgId, f, "LATE");
            case "hr-absent"             -> attendance(orgId, f, "ABSENT");
            case "hr-attendance-summary" -> attendanceSummary(orgId, f);
            case "hr-overtime"           -> overtime(orgId, f);
            case "hr-overtime-claims"    -> overtimeClaims(orgId, f);
            case "hr-leave-register"     -> leaveRegister(orgId, f, "");
            case "hr-leave-pending"      -> leaveRegister(orgId, f, "SUBMITTED");
            case "hr-leave-balance"      -> leaveBalance(orgId, f);
            case "hr-leave-utilisation"  -> leaveUtilisation(orgId, f);
            case "hr-salary-register"    -> salaryRegister(orgId, f);
            case "hr-increment"          -> increments(orgId, f);
            case "hr-pf"                 -> providentFund(orgId, f);
            case "hr-gratuity"           -> gratuityAwards(orgId, f);
            case "hr-expense"            -> expenseClaims(orgId, f);
            case "hr-advance"            -> advances(orgId, f);
            case "hr-loan"               -> loans(orgId, f);
            case "hr-payroll-register"   -> payrollRegister(orgId, f);
            case "hr-payroll-summary"    -> payrollSummary(orgId, f);
            case "hr-payroll-cost"       -> payrollCost(orgId, f);
            case "hr-payroll-deduction"  -> payrollDeductions(orgId, f);
            case "hr-payroll-exception"  -> payrollExceptions(orgId, f);
            default -> throw new ValidationException("'" + id + "' is not an HR report.");
        };
    }

    /* ══════════════════════════════════════════════════════════════════════
       PEOPLE
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse employees(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.employeeRegister(orgId, f.branchId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"), col("designation", "Designation", "text"),
                col("empType", "Role", "text"), col("employmentType", "Engaged as", "text"),
                col("hireDate", "Joined", "date"), col("phone", "Phone", "text"),
                money("basic", "Basic"), col("state", "Status", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], label(r[4]), label(r[5]),
                         r[6], r[7], r[8], r[9]));
        }
        return grid("hr-employees", "Employee Register", cols, rows,
                "Inactive staff are included and marked. Somebody who left in March is part of "
                + "the year's record, and a register that drops them cannot be reconciled against "
                + "a payroll run that paid them.",
                List.of("branch", "search"));
    }

    private ReportGridResponse headcount(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.headcount(orgId, f.branchId());
        var cols = List.of(
                col("department", "Department", "text"), col("designation", "Designation", "text"),
                num("headcount", "Staff"), num("permanent", "Permanent"), num("probation", "Probation"),
                num("notReady", "Not payroll ready"), money("basicTotal", "Basic total"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6]));
        return grid("hr-headcount", "Headcount", cols, rows,
                "Active staff only. 'Not payroll ready' is anybody without a basic salary or "
                + "without a salary structure — payroll skips them entirely.",
                List.of("branch"));
    }

    private ReportGridResponse separations(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.separations(orgId, f.branchId(), f.windowFrom(), f.windowTo(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"), col("type", "Reason", "text"),
                col("noticeDate", "Notice", "date"), col("lastDay", "Last day", "date"),
                col("state", "Status", "text"), col("settlement", "Settlement", "text"),
                money("amount", "Amount"), col("note", "Note", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], label(r[3]), r[4], r[5],
                         label(r[6]), label(r[7]), r[8], r[9]));
        }
        return grid("hr-separation", "Employee Separation", cols, rows,
                "By last working day. The settlement amount is entered rather than computed — "
                + "what belongs in a final settlement is policy, and this application does not "
                + "decide it.",
                List.of("dateRange", "branch", "search"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       ATTENDANCE
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse attendance(Long orgId, ReportGridServiceImpl.Filters f, String fixed) {
        String wanted = fixed.isEmpty() ? f.statusFilter() : fixed;
        var raw = repo.attendanceDetail(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                        f.employeeId(), wanted, f.text());
        var cols = List.of(
                col("date", "Date", "date"), col("empId", "Code", "text"),
                col("name", "Name", "text"), col("department", "Department", "text"),
                col("state", "Status", "text"),
                col("checkIn", "In", "text"), col("checkOut", "Out", "text"),
                col("worked", "Worked", "text"), col("late", "Late", "text"),
                col("early", "Early", "text"), col("overtime", "Overtime", "text"),
                col("source", "Source", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], label(r[4]),
                         time(r[5]), time(r[6]),
                         hours(r[7]), minutes(r[8]), minutes(r[9]), hours(r[10]), label(r[11])));
        }

        String id    = "LATE".equals(fixed) ? "hr-late" : "ABSENT".equals(fixed) ? "hr-absent" : "hr-attendance";
        String title = CATALOGUE.get(id);
        String note  = switch (fixed) {
            case "LATE"   -> "Days marked late. Lateness is measured against the shift, once shifts "
                           + "are in use; until then it is what was entered.";
            case "ABSENT" -> "Days marked absent. A day covered by approved leave is LEAVE, not "
                           + "absent, and does not appear here.";
            default       -> "One row per attendance record. Durations are stored in minutes and "
                           + "shown in hours — a rounded hour is a complaint waiting to happen.";
        };
        return grid(id, title, cols, rows, note,
                List.of("dateRange", "branch", "employee", "search"));
    }

    private ReportGridResponse attendanceSummary(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.attendanceSummary(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                         f.employeeId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                num("present", "Present"), num("halfDay", "Half day"), num("absent", "Absent"),
                num("leave", "Leave"), num("lateDays", "Late days"),
                col("lateTime", "Late total", "text"), col("overtime", "Overtime", "text"),
                col("worked", "Worked", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7],
                         minutes(r[8]), hours(r[9]), hours(r[10])));
        }
        return grid("hr-attendance-summary", "Monthly Attendance Summary", cols, rows,
                "One row per employee for the window. This is what payroll counts a working month "
                + "from — a discrepancy here is a discrepancy on the payslip.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    /**
     * Overtime, with what it is worth.
     *
     * <p>The rate is recomputed here from the same inputs {@code PayrollCalculator}
     * uses — basic over working days over standard hours, times the multiplier —
     * because this report runs over a date window rather than a payroll period,
     * so there is no payslip to read the figure off. Where the structure names no
     * multiplier the value column says so in words rather than showing a zero,
     * which would read as "no overtime" instead of "overtime is not paid here".
     */
    private ReportGridResponse overtime(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.overtime(orgId, f.branchId(), f.windowFrom(), f.windowTo(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                num("days", "Days"), col("hours", "Hours", "text"),
                col("structure", "Structure", "text"), col("rate", "Rate", "text"),
                col("value", "Value", "text"));

        long workingDays = Math.max(1, java.time.temporal.ChronoUnit.DAYS
                .between(f.windowFrom(), f.windowTo()) + 1);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            int mins = ((Number) r[4]).intValue();
            BigDecimal multiplier = r[6] == null ? null : bd(r[6]);
            BigDecimal basic      = bd(r[7]);
            BigDecimal hoursPerDay = r[8] == null ? null : bd(r[8]);

            String rate, value;
            if (multiplier == null || multiplier.signum() <= 0
                    || hoursPerDay == null || hoursPerDay.signum() <= 0 || basic.signum() <= 0) {
                rate  = "not paid";
                value = "recorded only";
            } else {
                BigDecimal hourly = basic
                        .divide(BigDecimal.valueOf(workingDays), 6, RoundingMode.HALF_UP)
                        .divide(hoursPerDay, 6, RoundingMode.HALF_UP)
                        .multiply(multiplier);
                rate  = multiplier.stripTrailingZeros().toPlainString() + "x";
                value = hourly.multiply(BigDecimal.valueOf(mins))
                              .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                              .toPlainString();
            }
            rows.add(row(cols, r[0], r[1], r[2], r[3], hours(r[4]), r[5], rate, value));
        }
        return grid("hr-overtime", "Overtime", cols, rows,
                "Only staff with overtime recorded. Where the salary structure names no multiplier "
                + "the hours are still recorded and shown, and the value reads 'recorded only' — "
                + "that is overtime deliberately unpaid, not a missing figure. The value here is "
                + "indicative: the payslip is what was actually paid.",
                List.of("dateRange", "branch", "search"));
    }

    /**
     * The claim workflow behind part of the overtime on the register.
     *
     * <p>Reported separately from {@link #overtime} on purpose. That report is
     * the register — the figure payroll values. This one is the paper trail,
     * and the two differ wherever overtime was entered directly on a day rather
     * than claimed for it. Showing the difference is the point; reconciling it
     * away would hide exactly the thing somebody opens this to find.
     */
    private ReportGridResponse overtimeClaims(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.overtimeClaims(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                      f.status() == null ? "" : f.status().trim().toUpperCase(),
                                      f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                col("otDate", "Date", "date"),
                col("claimed", "Claimed", "text"), col("approved", "Approved", "text"),
                col("status", "Status", "text"),
                col("submittedBy", "Claimed by", "text"), col("decidedBy", "Decided by", "text"),
                col("reason", "Reason", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3],
                         hours(r[4]),
                         "APPROVED".equals(String.valueOf(r[6])) ? hours(r[5]) : "—",
                         label(r[6]), r[7], r[8], r[9]));
        }
        return grid("hr-overtime-claims", "Overtime Claims", cols, rows,
                "The claims, not the register. Approving a claim writes its minutes onto the "
                + "attendance row for that day, and payroll values that row — so a claim never "
                + "reaches a payslip twice. Overtime entered straight onto a day appears in the "
                + "Overtime report and not here, which is why the two totals can differ.",
                List.of("dateRange", "branch", "status", "search"));
    }

    /**
     * The increment register — the salary history.
     *
     * <p>Every figure is read exactly as it was frozen at approval. Working a
     * percentage out again here would print a number nobody signed off, and
     * would disagree with the one that was applied.
     */
    private ReportGridResponse increments(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.increments(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                  f.status() == null ? "" : f.status().trim().toUpperCase(),
                                  f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                col("type", "Kind", "text"), col("basis", "Basis", "text"),
                col("previous", "From", "money"), money("amount", "Rise"),
                col("current", "To", "money"),
                col("effectiveDate", "Effective", "date"), col("status", "Status", "text"),
                col("decidedBy", "Decided by", "text"), col("reason", "Reason", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal amount = bd(r[7]);
            String basis = "PERCENT".equals(String.valueOf(r[4]))
                    ? bd(r[5]).stripTrailingZeros().toPlainString() + "%"
                    : "Fixed";
            rows.add(row(cols, r[0], r[1], r[2], label(r[3]), basis,
                         bd(r[6]), amount, bd(r[8]), r[9], label(r[10]), r[11], r[12]));
        }
        return grid("hr-increment", "Increment Register", cols, rows,
                "The salary history: the employee record holds only the current figure. Money is "
                + "shown as it was frozen when the increment was approved, never worked out again "
                + "— a percentage recomputed later would print a figure nobody agreed to. The "
                + "Rise total covers every row in view; filter to Applied for the monthly cost of "
                + "the increments that actually took effect.",
                List.of("dateRange", "branch", "status", "search"));
    }

    /**
     * The provident fund.
     *
     * <p>Empty at a branch with no scheme, which is the shipped state and is the
     * honest answer: no rate has been decided, so nothing has been contributed.
     * The balance column is the whole fund to date rather than the window's — a
     * balance that counted only the last month would reconcile against nothing.
     */
    private ReportGridResponse providentFund(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.providentFund(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                     f.employeeId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                col("entryDate", "Date", "date"), col("kind", "Kind", "text"),
                col("base", "On", "money"), col("rates", "Rates", "text"),
                money("employee", "Employee"), money("employer", "Employer"),
                money("movement", "Movement"),
                col("balance", "Balance", "money"),
                col("remarks", "Note", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal empPct = bd(r[6]), erPct = bd(r[7]);
            String rates = (empPct.signum() > 0 || erPct.signum() > 0)
                    ? empPct.stripTrailingZeros().toPlainString() + "% / "
                      + erPct.stripTrailingZeros().toPlainString() + "%"
                    : "—";
            rows.add(row(cols, r[0], r[1], r[2], r[3], label(r[4]),
                         bd(r[5]), rates, bd(r[8]), bd(r[9]), bd(r[10]), bd(r[11]), r[12]));
        }
        return grid("hr-pf", "Provident Fund", cols, rows,
                "Contributions appear when a payroll run is POSTED, not when it is calculated — a "
                + "run can be recalculated any number of times before that. The employer's share is "
                + "the company's money and is never deducted from a payslip. Empty where no scheme "
                + "has been set, because no rate has been decided rather than because it is nought.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    /**
     * Gratuity awards, with the working as it was frozen at approval.
     *
     * <p>Every figure is read. Recomputing the amount from today's policy and
     * today's salary would print one nobody signed off, and it would disagree
     * with what was actually paid.
     */
    private ReportGridResponse gratuityAwards(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.gratuityAwards(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                      f.status() == null ? "" : f.status().trim().toUpperCase(),
                                      f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                col("serviceFrom", "From", "date"), col("serviceTo", "To", "date"),
                num("years", "Years"),
                col("basis", "Valued on", "text"),
                col("wage", "Wage", "money"), col("daily", "Daily", "money"),
                num("days", "Days"), money("amount", "Amount"),
                col("status", "Status", "text"), col("decidedBy", "Decided by", "text"),
                col("paidDate", "Paid", "date"), col("reason", "Note", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], bd(r[5]),
                         label(r[6]), bd(r[7]), bd(r[8]), bd(r[10]), bd(r[11]),
                         label(r[12]), r[13], r[14], r[15]));
        }
        return grid("hr-gratuity", "Gratuity Awards", cols, rows,
                "The figures are the ones frozen when each award was approved, read back rather "
                + "than worked out again — recomputing them from today's policy would print a "
                + "number nobody signed off. Empty where no gratuity policy has been set. Paying an "
                + "award writes no final settlement: what belongs in one is still an open question.",
                List.of("dateRange", "branch", "status", "search"));
    }

    /**
     * Expense and conveyance claims, one row per line.
     *
     * <p>Line by line rather than claim by claim: the question this is opened to
     * answer is what the pharmacy is spending on conveyance, and a claim total
     * mixes the fares with the dinner.
     *
     * <p>Approved is blank until a claim is decided, rather than repeating the
     * claimed figure — a claim awaiting a decision has not been allowed
     * anything, and showing the two columns equal would say it had.
     */
    private ReportGridResponse expenseClaims(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.expenseClaims(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                     f.employeeId(),
                                     f.status() == null ? "" : f.status().trim().toUpperCase(),
                                     f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                col("type", "Kind", "text"),
                col("expenseDate", "Date", "date"),
                col("detail", "Detail", "text"),
                money("claimed", "Claimed"), money("approved", "Approved"),
                col("status", "Status", "text"),
                col("receipt", "Receipt", "text"),
                col("paidDate", "Paid", "date"),
                col("decidedBy", "Decided by", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5],
                         bd(r[6]),
                         r[7] == null ? null : bd(r[7]),
                         label(r[8]), r[9], r[10], r[11]));
        }
        return grid("hr-expense", "Expense & Conveyance", cols, rows,
                "One row per expense, not per claim, because a claim total mixes the fares with "
                + "the dinner. Reimbursement is paid on its own and never through a payslip — "
                + "money spent on the pharmacy's behalf is being returned, not earned, and putting "
                + "it in the gross would take tax and provident fund out of a rickshaw fare. "
                + "Approved is blank until a claim has been decided.",
                List.of("dateRange", "branch", "employee", "status", "search"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       LEAVE
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse leaveRegister(Long orgId, ReportGridServiceImpl.Filters f, String fixed) {
        String wanted = fixed.isEmpty() ? f.statusFilter() : fixed;
        var raw = repo.leaveRegister(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                     f.employeeId(), wanted, f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"), col("type", "Type", "text"),
                col("paid", "Paid", "text"),
                col("from", "From", "date"), col("to", "To", "date"),
                num("days", "Days"), col("state", "Status", "text"),
                col("decidedBy", "Decided by", "text"), col("reason", "Reason", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7],
                         label(r[8]), r[9], r[10]));
        }
        boolean pending = "SUBMITTED".equals(fixed);
        String id = pending ? "hr-leave-pending" : "hr-leave-register";
        return grid(id, CATALOGUE.get(id), cols, rows,
                pending ? "Requests still awaiting a decision. Each one is holding days against "
                        + "its employee's balance until it is approved or rejected."
                        : "Every request overlapping the window, whatever its state. Days were "
                        + "counted when the request was filed and are not recomputed — a calendar "
                        + "edited since must not change a closed month.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    private ReportGridResponse leaveBalance(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.leaveBalance(orgId, f.branchId(), f.leaveYear(), f.employeeId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"), col("type", "Type", "text"),
                num("entitled", "Entitled"), num("carried", "Carried"),
                num("taken", "Taken"), num("pending", "Pending"), num("available", "Available"),
                col("usedPct", "Used %", "percent"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
        }
        return grid("hr-leave-balance", "Leave Balance", cols, rows,
                "For " + f.leaveYear() + ". Available is entitled plus carried, less taken and "
                + "pending — a submitted request holds its days before anybody decides, or two "
                + "requests filed the same morning could both be approved into an overdraft.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    private ReportGridResponse leaveUtilisation(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.leaveUtilisation(orgId, f.branchId(), f.windowFrom(), f.windowTo());
        var cols = List.of(
                col("type", "Leave type", "text"), col("paid", "Paid", "text"),
                num("requests", "Requests"), num("employees", "Staff"), num("days", "Days"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[4]));
        return grid("hr-leave-utilisation", "Leave Utilisation", cols, rows,
                "Approved leave only, by type. Unpaid days are the ones payroll deducts; "
                + "everything else is absence the employer has already agreed to pay for.",
                List.of("dateRange", "branch"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       MONEY
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse salaryRegister(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.salaryRegister(orgId, f.branchId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"), col("designation", "Designation", "text"),
                col("structure", "Structure", "text"), money("basic", "Basic"),
                col("payMode", "Paid by", "text"), col("bank", "Bank", "text"),
                col("ready", "Payroll", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], label(r[6]), r[7], r[8]));
        }
        return grid("hr-salary-register", "Salary Register", cols, rows,
                "The current pay basis, not a payroll run. Basic is what a structure computes "
                + "from — the gross on a payslip also carries allowances and overtime.",
                List.of("branch", "search"));
    }

    private ReportGridResponse advances(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.advanceRegister(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                       f.employeeId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("date", "Requested", "date"),
                money("asked", "Asked"), money("approved", "Approved"),
                money("recovered", "Recovered"), money("outstanding", "Outstanding"),
                num("installments", "Over"), col("state", "Status", "text"),
                col("paidDate", "Paid", "date"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], label(r[8]), r[9]));
        }
        return grid("hr-advance", "Advance Register", cols, rows,
                "Asked and Approved are kept apart on purpose: an approver who cut the figure made "
                + "a decision worth seeing, and every recovery is computed from the approved amount.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    private ReportGridResponse loans(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.loanRegister(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                    f.employeeId(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("date", "Requested", "date"),
                money("principal", "Principal"), money("approved", "Approved"),
                num("installments", "Installments"), money("perMonth", "Per month"),
                money("recovered", "Recovered"), money("outstanding", "Outstanding"),
                num("paidInstallments", "Paid"), col("state", "Status", "text"),
                col("disbursed", "Disbursed", "date"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9],
                         label(r[10]), r[11]));
        }
        return grid("hr-loan", "Loan Register", cols, rows,
                "Interest is recorded on a loan and never applied — nothing in this application "
                + "computes it, and whether it is charged at all is an unanswered policy question.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       PAYROLL
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse payrollRegister(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.payrollRegister(orgId, f.branchId(), f.windowFrom(), f.windowTo(),
                                       f.employeeId(), f.text());
        var cols = List.of(
                col("payrollNo", "Run", "text"), col("period", "Period", "text"),
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("department", "Department", "text"),
                money("basic", "Basic"), money("allowances", "Allowances"),
                money("overtime", "Overtime"), money("bonus", "Bonus"),
                money("gross", "Gross"), money("deductions", "Deductions"), money("net", "Net"),
                col("paidState", "Paid", "text"), money("paid", "Paid amount"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], period(r[1], r[2]), r[3], r[4], r[5],
                         r[6], r[7], r[8], r[9], r[10], r[11], r[12], label(r[13]), r[14]));
        }
        return grid("hr-payroll-register", "Payroll Register", cols, rows,
                "One row per payslip. Cancelled runs are excluded — an abandoned run is evidence "
                + "it happened, not a salary anybody was paid. Every figure is read from the "
                + "payslip as calculated, never recomputed here.",
                List.of("dateRange", "branch", "employee", "search"));
    }

    private ReportGridResponse payrollSummary(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.payrollSummary(orgId, f.branchId(), f.windowFrom(), f.windowTo());
        var cols = List.of(
                col("payrollNo", "Run", "text"), col("period", "Period", "text"),
                col("state", "Status", "text"), num("employees", "Payslips"),
                money("gross", "Gross"), money("deductions", "Deductions"),
                money("tax", "Tax"), money("net", "Net"), money("paid", "Paid"),
                money("unpaid", "Owing"),
                col("approvedBy", "Approved by", "text"), col("postedAt", "Posted", "date"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], period(r[1], r[2]), label(r[3]), r[4],
                         r[5], r[6], r[7], r[8], r[9], r[10], r[11], r[12]));
        }
        return grid("hr-payroll-summary", "Payroll Summary", cols, rows,
                "One row per run, including cancelled ones, so a month that was started and "
                + "abandoned is visible rather than missing. Tax is always zero: no rate is "
                + "configured anywhere in this application.",
                List.of("dateRange", "branch"));
    }

    private ReportGridResponse payrollCost(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.payrollCost(orgId, f.branchId(), f.windowFrom(), f.windowTo());
        var cols = List.of(
                col("department", "Department", "text"), num("payslips", "Payslips"),
                money("basic", "Basic"), money("allowances", "Allowances"),
                money("overtime", "Overtime"), money("bonus", "Bonus"),
                money("gross", "Gross"), money("deductions", "Deductions"), money("net", "Net"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        }
        return grid("hr-payroll-cost", "Payroll Cost by Department", cols, rows,
                "Gross is the cost to the business; net is what staff receive. The difference is "
                + "deductions, which include recoveries of money the business already advanced — "
                + "so net is not a smaller cost, it is the same cost less what came back.",
                List.of("dateRange", "branch"));
    }

    private ReportGridResponse payrollDeductions(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.payrollDeductions(orgId, f.branchId(), f.windowFrom(), f.windowTo(), f.text());
        var cols = List.of(
                col("empId", "Code", "text"), col("name", "Name", "text"),
                col("payrollNo", "Run", "text"),
                money("absence", "Absence"), money("advance", "Advance"),
                money("loan", "Loan"), money("tax", "Tax"), money("other", "Structure"),
                money("total", "Total"), money("net", "Net"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
        }
        return grid("hr-payroll-deduction", "Payroll Deductions", cols, rows,
                "'Structure' is what the salary structure deducted — provident fund and the like. "
                + "Advance and loan are recoveries of money already paid out, and each is capped "
                + "at what the month contained, so an unrecovered balance stays owing.",
                List.of("dateRange", "branch", "search"));
    }

    /**
     * What would make a payroll wrong, before anybody approves it.
     *
     * <p>Severity comes from the query rather than from here, because it is a
     * property of the fault: an employee with no salary is an ERROR whoever is
     * looking at it.
     */
    private ReportGridResponse payrollExceptions(Long orgId, ReportGridServiceImpl.Filters f) {
        var raw = repo.payrollExceptions(orgId, f.branchId(), f.windowFrom(), f.windowTo());
        var cols = List.of(
                col("severity", "Severity", "text"), col("empId", "Code", "text"),
                col("name", "Name", "text"), col("issue", "Issue", "text"),
                col("effect", "What happens", "text"));

        List<Map<String, Object>> rows = new ArrayList<>();
        long errors = 0;
        for (Object[] r : raw) {
            if ("ERROR".equals(r[0])) errors++;
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4]));
        }
        return grid("hr-payroll-exception", "Payroll Exceptions", cols, rows,
                (errors == 0
                    ? "Nothing blocking. "
                    : errors + (errors == 1 ? " error" : " errors") + " would affect the run. ")
                + "Run this over the pay period before calculating. An ERROR means somebody will "
                + "be skipped or cannot be paid; a WARNING means the figures will compute but may "
                + "not be what was intended.",
                List.of("dateRange", "branch"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       Formatting
       ══════════════════════════════════════════════════════════════════════ */

    /** "PART_TIME" reads as "Part Time" on a report, not as a constant. */
    private static String label(Object raw) {
        if (raw == null) return "";
        String s = raw.toString();
        if (s.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0)))
               .append(w.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    /** Minutes to "7h 30m". The one place attendance minutes become readable. */
    private static String hours(Object raw) {
        int m = raw == null ? 0 : ((Number) raw).intValue();
        if (m == 0) return "—";
        int h = m / 60, rest = m % 60;
        return h == 0 ? rest + "m" : (rest == 0 ? h + "h" : h + "h " + rest + "m");
    }

    /** Small durations stay in minutes: "25m late" is clearer than "0h 25m". */
    private static String minutes(Object raw) {
        int m = raw == null ? 0 : ((Number) raw).intValue();
        return m == 0 ? "—" : m + "m";
    }

    /** A timestamp as a wall clock. The date is already the row's own column. */
    private static String time(Object raw) {
        if (raw == null) return "—";
        String s = raw.toString();
        int t = s.indexOf(' ') >= 0 ? s.indexOf(' ') : s.indexOf('T');
        return t < 0 || s.length() < t + 6 ? s : s.substring(t + 1, t + 6);
    }

    /** Year and month as "September 2026". */
    private static String period(Object year, Object month) {
        if (year == null || month == null) return "";
        int m = ((Number) month).intValue();
        if (m < 1 || m > 12) return year.toString();
        return java.time.Month.of(m)
                .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + " " + year;
    }
}
