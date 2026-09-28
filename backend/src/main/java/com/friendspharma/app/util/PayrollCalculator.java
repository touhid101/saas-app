package com.friendspharma.app.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Payslip arithmetic — the single source of truth for every figure on a payslip.
 *
 * <p>Pure functions, no Spring, no I/O, so the rules are unit testable and the
 * calculate and recalculate paths cannot drift apart. The same shape as
 * {@link PurchaseCalculator} and {@link SalesPriceCalculator}, and for the same
 * reason: money arithmetic that lives inside a service is arithmetic nobody can
 * test without a database.
 *
 * <p><b>The order of operations, in full.</b> Each step depends on the one
 * before it, which is why the component sort order is part of the contract
 * rather than presentation:
 *
 * <pre>
 *   1  basic          = employee's BASIC_SALARY
 *   2  earnings       walk EARNING components in sort order, accumulating:
 *                        FIXED         amount
 *                        PCT_OF_BASIC  basic x pct / 100
 *                        PCT_OF_GROSS  runningGross x pct / 100
 *                     runningGross starts at basic
 *      allowances    = runningGross - basic
 *   3  overtime       = 0 when the structure pays none, otherwise
 *                       basic / workingDays / hoursPerDay x multiplier x otHours
 *   4  bonus          as entered for this run
 *   5  gross          = basic + allowances + overtime + bonus
 *   6  absence        = (absenceBasis = GROSS ? gross : basic) / workingDays
 *                       x (absentDays + unpaidLeaveDays)
 *   7  components     walk DEDUCTION components the same way, against gross
 *   8  provident fund = 0 unless a scheme names a rate; see below
 *   9  tax            = 0. No rate is configured anywhere in this application
 *  10  recoveries     advance first, then loan, each capped at what is left
 *  11  net            = gross - every deduction above, never below zero
 * </pre>
 *
 * <p><b>Why recoveries are capped, and why advances go first.</b> The database
 * refuses a negative payslip ({@code ck_hr_line_net}), and it is right to: a
 * negative net means the recovery plan took more than the month contained,
 * which is a calculation fault rather than a payment. So a recovery takes what
 * is there and no more, and the rest stays outstanding for next month. An
 * advance is recovered before a loan because an advance is this month's salary
 * already handed over, while a loan is a longer arrangement that was always
 * going to take several months.
 *
 * <p><b>Tax.</b> {@link #TAX_NOT_CONFIGURED} is zero and is returned by every
 * calculation. Nothing in this application holds a tax rate, a slab, or an
 * exemption, and inventing one would put a number on a payslip that no
 * accountant chose. Marked BUSINESS / TAX POLICY CONFIRMATION REQUIRED in
 * {@code 11-financial-period-tax.md}. The {@code isTaxable} flag on each
 * component is carried through so that a rate, once configured, has a base to
 * apply to.
 *
 * <p><b>Provident fund.</b> {@link Pf} is null unless the branch has a scheme
 * with a rate on it, and null means no PF line at all — the payslip reads
 * exactly as it did before schemes existed. A null rate is <em>not</em> nought
 * per cent; it is nobody having decided, which is why it is absent rather than
 * printed as zero. Marked BUSINESS / TAX POLICY CONFIRMATION REQUIRED (P-07).
 *
 * <p>PF is deducted <b>before</b> the advance and loan recoveries and after the
 * structure's own deduction components. That ordering is a decision: a
 * recovery is capped at what the month has left, so putting PF first means a
 * tight month short-pays the loan rather than the fund. Money owed to a fund is
 * not the same kind of debt as money owed back to the employer.
 *
 * <p>The employer's share is computed and returned but is <b>not</b> a
 * deduction and never reaches the net — it is the company's money. It exists on
 * the result so the cost of employing somebody can be reported without a second
 * calculation.
 *
 * <p><b>A component marked {@link Component#pf()} is skipped</b> when a scheme
 * is in force, because a structure can already carry a PF deduction as an
 * ordinary component and deducting both would take it twice. With no scheme,
 * that component is evaluated exactly as any other. Nothing is inferred from a
 * component's name.
 *
 * <p>Money rounds to 2 decimal places, HALF_UP, at each step — the same
 * convention as every other calculator here.
 */
public final class PayrollCalculator {

    public static final String EARNING   = "EARNING";
    public static final String DEDUCTION = "DEDUCTION";

    public static final String FIXED        = "FIXED";
    public static final String PCT_OF_BASIC = "PCT_OF_BASIC";
    public static final String PCT_OF_GROSS = "PCT_OF_GROSS";

    public static final String ABSENCE_ON_BASIC = "BASIC";
    public static final String ABSENCE_ON_GROSS = "GROSS";

    /** The basic pay line, which every payslip carries whether or not a structure names it. */
    public static final String BASIC_CODE = "BASIC";
    public static final String BASIC_NAME = "Basic Salary";
    public static final String OVERTIME_CODE = "OVERTIME";
    public static final String OVERTIME_NAME = "Overtime";
    public static final String BONUS_CODE = "BONUS";
    public static final String BONUS_NAME = "Bonus";
    public static final String ABSENCE_CODE = "ABSENCE";
    public static final String ABSENCE_NAME = "Absence Deduction";
    public static final String ADVANCE_CODE = "ADVANCE";
    public static final String ADVANCE_NAME = "Advance Recovery";
    public static final String LOAN_CODE = "LOAN";
    public static final String LOAN_NAME = "Loan Recovery";

    public static final String PF_CODE = "PF";
    public static final String PF_NAME = "Provident Fund";

    /** The two wages a PF percentage can be taken on. */
    public static final String PF_ON_BASIC = "BASIC";
    public static final String PF_ON_GROSS = "GROSS";

    /** No tax rate exists in this application. See the class comment. */
    public static final BigDecimal TAX_NOT_CONFIGURED = BigDecimal.ZERO;

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SIXTY   = new BigDecimal("60");

    private PayrollCalculator() {}

    /* ── Inputs ─────────────────────────────────────────────────────────── */

    /**
     * One line of a salary structure, flattened so the calculator needs no JPA.
     *
     * @param code      short code, carried onto the payslip
     * @param name      what the payslip calls it
     * @param type      {@link #EARNING} or {@link #DEDUCTION}
     * @param method    {@link #FIXED}, {@link #PCT_OF_BASIC} or {@link #PCT_OF_GROSS}
     * @param amount    read when the method is FIXED
     * @param pctValue  read when the method is a percentage
     * @param taxable   whether the line sits inside the base a tax rate would apply to
     * @param sortOrder the order the line is evaluated in — see the class comment
     */
    public record Component(String code, String name, String type, String method,
                            BigDecimal amount, BigDecimal pctValue,
                            boolean taxable, boolean pf, int sortOrder) {}

    /**
     * The provident fund terms in force, or null when there are none.
     *
     * <p>Null is the shipped state and the honest one: no branch has a scheme
     * until somebody enters the rates, and until then no PF is deducted from
     * anybody. A rate of null inside a scheme means the same thing for that
     * share alone — a scheme where only the employer contributes is a real
     * arrangement, and so is one where only the employee does.
     *
     * @param employeePct what comes off the payslip, or null for none
     * @param employerPct what the company adds, or null for none
     * @param base        {@link #PF_ON_BASIC} or {@link #PF_ON_GROSS}
     * @param ceiling     the most wage a contribution is taken on; null for no ceiling
     */
    public record Pf(BigDecimal employeePct, BigDecimal employerPct,
                     String base, BigDecimal ceiling) {

        /** True when at least one share has a rate somebody actually chose. */
        public boolean pays() {
            return (employeePct != null && employeePct.signum() > 0)
                || (employerPct != null && employerPct.signum() > 0);
        }
    }

    /**
     * Everything one payslip is computed from.
     *
     * @param basic            the employee's BASIC_SALARY
     * @param workingDays      days in the pay period, from the period, not a calendar
     * @param absentDays       days recorded ABSENT
     * @param unpaidLeaveDays  approved leave on a type marked unpaid
     * @param overtimeMinutes  minutes, never hours — see {@code HrAttendance}
     * @param bonus            ad hoc, entered for this run
     * @param hoursPerDay      from the structure; null means overtime is unpaid
     * @param otMultiplier     from the structure; null means overtime is unpaid
     * @param absenceBasis     {@link #ABSENCE_ON_BASIC} or {@link #ABSENCE_ON_GROSS}
     * @param advanceDue       what the advance plan asks for this month
     * @param loanDue          what the loan schedule asks for this month
     * @param components       the structure's lines, any order; this class sorts them
     */
    public record Input(BigDecimal basic,
                        BigDecimal workingDays,
                        BigDecimal absentDays,
                        BigDecimal unpaidLeaveDays,
                        int overtimeMinutes,
                        BigDecimal bonus,
                        BigDecimal hoursPerDay,
                        BigDecimal otMultiplier,
                        String absenceBasis,
                        BigDecimal advanceDue,
                        BigDecimal loanDue,
                        Pf pf,
                        List<Component> components) {}

    /* ── Output ─────────────────────────────────────────────────────────── */

    /**
     * One line of the arithmetic, as it will appear on the payslip.
     *
     * <p>{@code base} and {@code pct} are carried beside the result so the row
     * reads as a sentence — "House Rent, 40% of 20,000, = 8,000" — and stays
     * checkable after somebody edits the structure it came from.
     */
    public record ResultLine(String code, String name, String type, String method,
                             BigDecimal base, BigDecimal pct, BigDecimal amount, int sortOrder) {}

    /** A finished payslip. Every figure the database stores, plus its explanation. */
    public record Result(BigDecimal basic,
                         BigDecimal allowanceTotal,
                         BigDecimal overtimeAmount,
                         BigDecimal bonusAmount,
                         BigDecimal grossSalary,
                         BigDecimal componentDeductions,
                         BigDecimal absenceDeduction,
                         BigDecimal advanceRecovery,
                         BigDecimal loanRecovery,
                         BigDecimal pfEmployee,
                         BigDecimal pfEmployer,
                         BigDecimal taxAmount,
                         BigDecimal deductionTotal,
                         BigDecimal netSalary,
                         BigDecimal taxableEarnings,
                         List<ResultLine> lines) {}

    /* ── The calculation ────────────────────────────────────────────────── */

    public static Result calculate(Input in) {
        List<ResultLine> lines = new ArrayList<>();

        BigDecimal basic = round(nz(in.basic()));
        lines.add(new ResultLine(BASIC_CODE, BASIC_NAME, EARNING, FIXED,
                                 null, null, basic, 0));

        /* ── 2. Earnings ──
           runningGross starts at basic, so a PCT_OF_GROSS earning early in the
           order computes against basic alone and one late in the order computes
           against basic plus everything before it. That is the difference the
           sort order exists to express. */
        BigDecimal runningGross   = basic;
        BigDecimal taxableEarning = basic;   // basic is taxable by convention here

        for (Component c : sorted(in.components(), EARNING)) {
            BigDecimal base = switch (safe(c.method())) {
                case PCT_OF_BASIC -> basic;
                case PCT_OF_GROSS -> runningGross;
                default           -> null;
            };
            BigDecimal amount = valueOf(c, basic, runningGross);
            if (amount.signum() == 0) continue;

            runningGross = runningGross.add(amount);
            if (c.taxable()) taxableEarning = taxableEarning.add(amount);

            lines.add(new ResultLine(c.code(), c.name(), EARNING, c.method(),
                                     base, c.pctValue(), amount, c.sortOrder()));
        }
        BigDecimal allowanceTotal = round(runningGross.subtract(basic));

        /* ── 3. Overtime ── */
        BigDecimal overtime = overtimePay(basic, in.workingDays(), in.hoursPerDay(),
                                          in.otMultiplier(), in.overtimeMinutes());
        if (overtime.signum() > 0) {
            lines.add(new ResultLine(OVERTIME_CODE, OVERTIME_NAME, EARNING, FIXED,
                                     null, null, overtime, 900));
        }

        /* ── 4. Bonus ── */
        BigDecimal bonus = round(nz(in.bonus()));
        if (bonus.signum() > 0) {
            lines.add(new ResultLine(BONUS_CODE, BONUS_NAME, EARNING, FIXED,
                                     null, null, bonus, 910));
        }

        /* ── 5. Gross ── */
        BigDecimal gross = round(basic.add(allowanceTotal).add(overtime).add(bonus));

        /* ── 6. Absence ──
           Valued on basic unless the structure says gross. Guarded against a
           zero working-day count, which would otherwise divide by zero and take
           the whole run down over one badly-set-up period. */
        BigDecimal absenceDays = round(nz(in.absentDays()).add(nz(in.unpaidLeaveDays())));
        BigDecimal absence = BigDecimal.ZERO;
        BigDecimal workingDays = nz(in.workingDays());
        if (absenceDays.signum() > 0 && workingDays.signum() > 0) {
            BigDecimal absenceBase = ABSENCE_ON_GROSS.equals(safe(in.absenceBasis())) ? gross : basic;
            BigDecimal perDay = absenceBase.divide(workingDays, 4, RoundingMode.HALF_UP);
            absence = round(perDay.multiply(absenceDays));
            // An absence cannot cost more than the month is worth.
            if (absence.compareTo(absenceBase) > 0) absence = absenceBase;
            lines.add(new ResultLine(ABSENCE_CODE, ABSENCE_NAME, DEDUCTION, FIXED,
                                     absenceBase, null, absence, 920));
        }

        /* ── 7. Deduction components, against the finished gross ── */
        BigDecimal componentDeductions = BigDecimal.ZERO;
        boolean schemeInForce = in.pf() != null && in.pf().pays();
        for (Component c : sorted(in.components(), DEDUCTION)) {
            /* The scheme wins. A structure that already carries PF as an
               ordinary component would otherwise deduct it twice — once here
               and once at step 8 — and the payslip would show two PF lines
               that do not agree. */
            if (schemeInForce && c.pf()) continue;
            BigDecimal base = switch (safe(c.method())) {
                case PCT_OF_BASIC -> basic;
                case PCT_OF_GROSS -> gross;
                default           -> null;
            };
            BigDecimal amount = valueOf(c, basic, gross);
            if (amount.signum() == 0) continue;

            componentDeductions = componentDeductions.add(amount);
            lines.add(new ResultLine(c.code(), c.name(), DEDUCTION, c.method(),
                                     base, c.pctValue(), amount, c.sortOrder()));
        }
        componentDeductions = round(componentDeductions);

        /* ── 8. Provident fund ──
           Zero unless a scheme names a rate. The employee's share is a real
           deduction; the employer's is the company's own contribution and is
           returned beside it without touching the net. */
        BigDecimal pfBase     = BigDecimal.ZERO;
        BigDecimal pfEmployee = BigDecimal.ZERO;
        BigDecimal pfEmployer = BigDecimal.ZERO;
        if (schemeInForce) {
            pfBase     = pfWage(in.pf(), basic, gross);
            pfEmployee = pct(pfBase, in.pf().employeePct());
            pfEmployer = pct(pfBase, in.pf().employerPct());
            if (pfEmployee.signum() > 0) {
                lines.add(new ResultLine(PF_CODE, PF_NAME, DEDUCTION, PCT_OF_BASIC,
                                         pfBase, in.pf().employeePct(), pfEmployee, 925));
            }
        }

        /* ── 9. Tax — none configured anywhere in this application ── */
        BigDecimal tax = TAX_NOT_CONFIGURED;

        /* ── 10. Recoveries, capped at what the month has left ── */
        BigDecimal available = gross.subtract(componentDeductions)
                                    .subtract(absence)
                                    .subtract(pfEmployee)
                                    .subtract(tax);
        if (available.signum() < 0) available = BigDecimal.ZERO;

        BigDecimal advance = cap(round(nz(in.advanceDue())), available);
        available = available.subtract(advance);
        if (advance.signum() > 0) {
            lines.add(new ResultLine(ADVANCE_CODE, ADVANCE_NAME, DEDUCTION, FIXED,
                                     null, null, advance, 930));
        }

        BigDecimal loan = cap(round(nz(in.loanDue())), available);
        available = available.subtract(loan);
        if (loan.signum() > 0) {
            lines.add(new ResultLine(LOAN_CODE, LOAN_NAME, DEDUCTION, FIXED,
                                     null, null, loan, 940));
        }

        /* ── 11. Net.
           The employer's PF share is deliberately absent from this total: it is
           not taken from anybody's salary, and adding it would understate what
           the employee was actually paid. */
        BigDecimal deductionTotal = round(componentDeductions.add(absence)
                                                             .add(pfEmployee)
                                                             .add(advance)
                                                             .add(loan)
                                                             .add(tax));
        BigDecimal net = round(gross.subtract(deductionTotal));
        if (net.signum() < 0) net = BigDecimal.ZERO;   // unreachable given the caps, kept as a floor

        return new Result(basic, allowanceTotal, overtime, bonus, gross,
                          componentDeductions, absence, advance, loan,
                          pfEmployee, pfEmployer, tax,
                          deductionTotal, net, round(taxableEarning), lines);
    }

    /* ── Pieces, exposed so they can be tested and reused ────────────────── */

    /**
     * What overtime is worth.
     *
     * <p>Zero when the structure names no multiplier or no standard day: the
     * minutes are still recorded and still appear on the payslip as hours, but
     * they are not valued. That is the honest answer to "we have not decided
     * what overtime is worth here" — see {@code HrSalaryStructure}.
     */
    public static BigDecimal overtimePay(BigDecimal basic, BigDecimal workingDays,
                                         BigDecimal hoursPerDay, BigDecimal multiplier,
                                         int overtimeMinutes) {
        if (overtimeMinutes <= 0)                                    return BigDecimal.ZERO;
        if (multiplier  == null || multiplier.signum()  <= 0)        return BigDecimal.ZERO;
        if (hoursPerDay == null || hoursPerDay.signum() <= 0)        return BigDecimal.ZERO;
        if (workingDays == null || workingDays.signum() <= 0)        return BigDecimal.ZERO;
        if (basic       == null || basic.signum()       <= 0)        return BigDecimal.ZERO;

        BigDecimal hourly = basic.divide(workingDays, 6, RoundingMode.HALF_UP)
                                 .divide(hoursPerDay, 6, RoundingMode.HALF_UP);
        BigDecimal hours  = BigDecimal.valueOf(overtimeMinutes)
                                      .divide(SIXTY, 6, RoundingMode.HALF_UP);
        return round(hourly.multiply(multiplier).multiply(hours));
    }

    /**
     * The wage a PF percentage is taken on.
     *
     * <p>The ceiling is a cap on the <em>base</em>, not on the contribution:
     * capping the contribution instead would give a different answer whenever
     * the two shares differ, and it is the wage that statutes tend to cap.
     */
    public static BigDecimal pfWage(Pf pf, BigDecimal basic, BigDecimal gross) {
        if (pf == null) return BigDecimal.ZERO;
        BigDecimal wage = PF_ON_GROSS.equals(safe(pf.base())) ? nz(gross) : nz(basic);
        if (wage.signum() <= 0) return BigDecimal.ZERO;
        if (pf.ceiling() != null && pf.ceiling().signum() > 0
                && wage.compareTo(pf.ceiling()) > 0) {
            wage = pf.ceiling();
        }
        return round(wage);
    }

    /** What one component is worth, given the two bases a percentage can read. */
    public static BigDecimal valueOf(Component c, BigDecimal basic, BigDecimal gross) {
        if (c == null) return BigDecimal.ZERO;
        return switch (safe(c.method())) {
            case FIXED        -> round(nz(c.amount()));
            case PCT_OF_BASIC -> pct(basic, c.pctValue());
            case PCT_OF_GROSS -> pct(gross, c.pctValue());
            default           -> BigDecimal.ZERO;
        };
    }

    /* ── Helpers ─────────────────────────────────────────────────────────── */

    private static List<Component> sorted(List<Component> all, String type) {
        List<Component> out = new ArrayList<>();
        if (all == null) return out;
        for (Component c : all) {
            if (c != null && type.equals(safe(c.type()))) out.add(c);
        }
        out.sort((a, b) -> {
            int byOrder = Integer.compare(a.sortOrder(), b.sortOrder());
            return byOrder != 0 ? byOrder : safe(a.code()).compareTo(safe(b.code()));
        });
        return out;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal percentage) {
        if (base == null || percentage == null) return BigDecimal.ZERO;
        return round(base.multiply(percentage).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal cap(BigDecimal wanted, BigDecimal available) {
        if (wanted == null || wanted.signum() <= 0) return BigDecimal.ZERO;
        if (available == null || available.signum() <= 0) return BigDecimal.ZERO;
        return wanted.compareTo(available) > 0 ? available : wanted;
    }

    private static BigDecimal round(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
