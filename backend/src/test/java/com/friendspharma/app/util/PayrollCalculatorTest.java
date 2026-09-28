package com.friendspharma.app.util;

import com.friendspharma.app.util.PayrollCalculator.Component;
import com.friendspharma.app.util.PayrollCalculator.Input;
import com.friendspharma.app.util.PayrollCalculator.Pf;
import com.friendspharma.app.util.PayrollCalculator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payslip arithmetic.
 *
 * <p>The expected values are worked out by hand from the formula in the class
 * comment rather than copied from the implementation, so a change that alters
 * what somebody is paid shows up here as a failure rather than as a new
 * baseline.
 */
class PayrollCalculatorTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    /**
     * House rent at 40% of basic, medical at a flat 1,500, PF at 5% of basic.
     *
     * <p>The PF line here is an ordinary deduction component and is NOT marked
     * {@code pf}, which is how every structure that predates V82 looks. It is
     * therefore deducted normally, with or without a scheme — that is the
     * backwards-compatible behaviour, and the marked-component tests below are
     * what prove the alternative.
     */
    private static List<Component> standardStructure() {
        return List.of(
            new Component("HRA", "House Rent",  "EARNING",   "PCT_OF_BASIC", null,          bd("40"), true,  false, 10),
            new Component("MED", "Medical",     "EARNING",   "FIXED",        bd("1500"),    null,     true,  false, 20),
            new Component("PF",  "Provident Fund", "DEDUCTION", "PCT_OF_BASIC", null,       bd("5"),  false, false, 10)
        );
    }

    /** The same structure with its PF line marked as the PF deduction. */
    private static List<Component> structureWithMarkedPf() {
        return List.of(
            new Component("HRA", "House Rent",  "EARNING",   "PCT_OF_BASIC", null,       bd("40"), true,  false, 10),
            new Component("MED", "Medical",     "EARNING",   "FIXED",        bd("1500"), null,     true,  false, 20),
            new Component("PF",  "Provident Fund", "DEDUCTION", "PCT_OF_BASIC", null,    bd("5"),  false, true,  10)
        );
    }

    /* Named rather than inline, because an Input has twelve fields and a call
       that lists them raw is unreadable at the point it matters most. */
    private static Input input(BigDecimal basic, List<Component> components,
                               BigDecimal workingDays, BigDecimal absentDays,
                               BigDecimal unpaidLeave, int otMinutes, BigDecimal bonus,
                               BigDecimal hoursPerDay, BigDecimal otMultiplier,
                               String absenceBasis, BigDecimal advanceDue, BigDecimal loanDue) {
        return new Input(basic, workingDays, absentDays, unpaidLeave, otMinutes, bonus,
                         hoursPerDay, otMultiplier, absenceBasis, advanceDue, loanDue,
                         null, components);
    }

    private static Input withPf(BigDecimal basic, List<Component> components, Pf pf,
                                BigDecimal advanceDue, BigDecimal loanDue) {
        return new Input(basic, bd("26"), BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                         null, null, "BASIC", advanceDue, loanDue, pf, components);
    }

    private static Input plain(BigDecimal basic, List<Component> components) {
        return input(basic, components, bd("26"), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                     BigDecimal.ZERO, null, null, "BASIC", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("Provident fund")
    class ProvidentFund {

        @Test
        @DisplayName("with no scheme, nothing is deducted and nothing changes")
        void noScheme() {
            Result r = PayrollCalculator.calculate(plain(bd("20000"), structureWithMarkedPf()));

            /* The marked component is still deducted: marking it only matters
               once a scheme exists to replace it with. */
            assertThat(r.pfEmployee()).isEqualByComparingTo("0");
            assertThat(r.pfEmployer()).isEqualByComparingTo("0");
            assertThat(r.componentDeductions()).isEqualByComparingTo("1000");
            /* One PF line, and it is the component's 5% of 20,000 rather than any
               scheme figure — there is no scheme to produce one. */
            assertThat(r.lines()).filteredOn(l -> "PF".equals(l.code())).hasSize(1);
            assertThat(r.lines()).filteredOn(l -> "PF".equals(l.code()))
                    .first().extracting(l -> l.amount().stripTrailingZeros())
                    .isEqualTo(bd("1000").stripTrailingZeros());
        }

        @Test
        @DisplayName("a scheme with no rates on it deducts nothing")
        void schemeWithoutRates() {
            Pf undecided = new Pf(null, null, "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), List.of(), undecided, BigDecimal.ZERO, BigDecimal.ZERO));

            /* A null rate is nobody having decided, not nought per cent. The
               difference does not show in the arithmetic — both take nothing —
               but it is why the screen says "not set" rather than "0%". */
            assertThat(r.pfEmployee()).isEqualByComparingTo("0");
            assertThat(r.netSalary()).isEqualByComparingTo("20000");
        }

        @Test
        @DisplayName("employee and employer shares, on basic")
        void bothShares() {
            Pf pf = new Pf(bd("8"), bd("10"), "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), List.of(), pf, BigDecimal.ZERO, BigDecimal.ZERO));

            assertThat(r.pfEmployee()).isEqualByComparingTo("1600");   // 8% of 20,000
            assertThat(r.pfEmployer()).isEqualByComparingTo("2000");   // 10% of 20,000
            assertThat(r.deductionTotal()).isEqualByComparingTo("1600");
            assertThat(r.netSalary()).isEqualByComparingTo("18400");
        }

        @Test
        @DisplayName("the employer's share never reaches the net")
        void employerShareIsNotADeduction() {
            Pf pf = new Pf(null, bd("10"), "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), List.of(), pf, BigDecimal.ZERO, BigDecimal.ZERO));

            /* The company contributes 2,000 and the employee is paid the whole
               20,000. Putting the employer's share in the deduction total would
               understate what somebody was actually paid. */
            assertThat(r.pfEmployer()).isEqualByComparingTo("2000");
            assertThat(r.deductionTotal()).isEqualByComparingTo("0");
            assertThat(r.netSalary()).isEqualByComparingTo("20000");
        }

        @Test
        @DisplayName("on gross, the base is the finished gross")
        void onGross() {
            Pf pf = new Pf(bd("10"), null, "GROSS", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), standardStructure(), pf, BigDecimal.ZERO, BigDecimal.ZERO));

            // 20,000 + 8,000 house rent + 1,500 medical = 29,500 gross
            assertThat(r.grossSalary()).isEqualByComparingTo("29500");
            assertThat(r.pfEmployee()).isEqualByComparingTo("2950");
        }

        @Test
        @DisplayName("a ceiling caps the wage, not the contribution")
        void ceiling() {
            Pf pf = new Pf(bd("10"), bd("10"), "BASIC", bd("15000"));
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), List.of(), pf, BigDecimal.ZERO, BigDecimal.ZERO));

            /* Both shares are 10% of 15,000, not of 20,000. Capping the
               contribution instead would give a different answer whenever the
               two rates differ. */
            assertThat(PayrollCalculator.pfWage(pf, bd("20000"), bd("20000")))
                    .isEqualByComparingTo("15000");
            assertThat(r.pfEmployee()).isEqualByComparingTo("1500");
            assertThat(r.pfEmployer()).isEqualByComparingTo("1500");
        }

        @Test
        @DisplayName("THE POINT: a marked component is skipped so PF is not taken twice")
        void markedComponentIsSkipped() {
            Pf pf = new Pf(bd("8"), null, "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), structureWithMarkedPf(), pf,
                           BigDecimal.ZERO, BigDecimal.ZERO));

            /* The structure's own 5% PF line is skipped; the scheme's 8% is
               taken. Without the skip the payslip would carry both — 1,000 and
               1,600 — and deduct 2,600 for one fund. */
            assertThat(r.componentDeductions()).isEqualByComparingTo("0");
            assertThat(r.pfEmployee()).isEqualByComparingTo("1600");
            assertThat(r.deductionTotal()).isEqualByComparingTo("1600");
            assertThat(r.lines()).filteredOn(l -> "PF".equals(l.code())).hasSize(1);
        }

        @Test
        @DisplayName("an unmarked component is deducted alongside the scheme")
        void unmarkedComponentIsNotSkipped() {
            Pf pf = new Pf(bd("8"), null, "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("20000"), standardStructure(), pf,
                           BigDecimal.ZERO, BigDecimal.ZERO));

            /* Deliberate, and the reason the scheme screen points at components
               that look like PF: the calculator will not guess from a name, so
               an unmarked one is exactly the double deduction somebody has to
               notice and fix by ticking the box. */
            assertThat(r.componentDeductions()).isEqualByComparingTo("1000");
            assertThat(r.pfEmployee()).isEqualByComparingTo("1600");
            assertThat(r.deductionTotal()).isEqualByComparingTo("2600");
        }

        @Test
        @DisplayName("PF is taken before a loan recovery, not after")
        void pfComesBeforeRecoveries() {
            Pf pf = new Pf(bd("50"), null, "BASIC", null);
            Result r = PayrollCalculator.calculate(
                    withPf(bd("10000"), List.of(), pf, BigDecimal.ZERO, bd("8000")));

            /* 10,000 gross, 5,000 to the fund, leaving 5,000 — so a loan asking
               for 8,000 gets 5,000 and the rest stays outstanding. Ordering it
               the other way would short-pay the fund instead, and money owed to
               a fund is not the same kind of debt as money owed the employer. */
            assertThat(r.pfEmployee()).isEqualByComparingTo("5000");
            assertThat(r.loanRecovery()).isEqualByComparingTo("5000");
            assertThat(r.netSalary()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("Earnings")
    class Earnings {

        @Test
        @DisplayName("basic alone, with no structure, is the gross")
        void basicOnly() {
            Result r = PayrollCalculator.calculate(plain(bd("20000"), List.of()));
            assertThat(r.basic()).isEqualByComparingTo("20000.00");
            assertThat(r.allowanceTotal()).isEqualByComparingTo("0.00");
            assertThat(r.grossSalary()).isEqualByComparingTo("20000.00");
            assertThat(r.netSalary()).isEqualByComparingTo("20000.00");
        }

        @Test
        @DisplayName("40% of 20,000 plus a flat 1,500 gives 9,500 of allowance")
        void percentageAndFixed() {
            Result r = PayrollCalculator.calculate(plain(bd("20000"), standardStructure()));
            // 20000 * 40% = 8000 ; + 1500 = 9500
            assertThat(r.allowanceTotal()).isEqualByComparingTo("9500.00");
            assertThat(r.grossSalary()).isEqualByComparingTo("29500.00");
            // PF 5% of basic = 1000
            assertThat(r.componentDeductions()).isEqualByComparingTo("1000.00");
            assertThat(r.netSalary()).isEqualByComparingTo("28500.00");
        }

        @Test
        @DisplayName("PCT_OF_GROSS reads the gross accumulated before it, so order changes the answer")
        void sortOrderChangesTheAnswer() {
            // A 10% of gross allowance placed BEFORE the 40% house rent sees
            // only basic; placed after, it sees basic + house rent.
            Component hra   = new Component("HRA", "House Rent", "EARNING", "PCT_OF_BASIC", null, bd("40"), true, false, 20);
            Component early = new Component("X", "Ten pct", "EARNING", "PCT_OF_GROSS", null, bd("10"), true, false, 10);
            Component late  = new Component("X", "Ten pct", "EARNING", "PCT_OF_GROSS", null, bd("10"), true, false, 30);

            Result first  = PayrollCalculator.calculate(plain(bd("20000"), List.of(hra, early)));
            Result second = PayrollCalculator.calculate(plain(bd("20000"), List.of(hra, late)));

            // early: 10% of 20000 = 2000, then HRA 40% of basic = 8000  -> 30000
            assertThat(first.grossSalary()).isEqualByComparingTo("30000.00");
            // late: HRA 8000 first (gross 28000), then 10% of 28000 = 2800 -> 30800
            assertThat(second.grossSalary()).isEqualByComparingTo("30800.00");
        }
    }

    @Nested
    @DisplayName("Overtime")
    class Overtime {

        @Test
        @DisplayName("unpaid when the structure names no multiplier, but the minutes still stand")
        void notPaidWithoutAMultiplier() {
            Input in = input(bd("26000"), List.of(), bd("26"), BigDecimal.ZERO, BigDecimal.ZERO,
                             600, BigDecimal.ZERO, bd("8"), null, "BASIC",
                             BigDecimal.ZERO, BigDecimal.ZERO);
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.overtimeAmount()).isEqualByComparingTo("0.00");
            assertThat(r.grossSalary()).isEqualByComparingTo("26000.00");
        }

        @Test
        @DisplayName("26,000 over 26 days of 8 hours is 125/hour; 10 hours at 2x is 2,500")
        void valuedFromBasic() {
            Input in = input(bd("26000"), List.of(), bd("26"), BigDecimal.ZERO, BigDecimal.ZERO,
                             600, BigDecimal.ZERO, bd("8"), bd("2"), "BASIC",
                             BigDecimal.ZERO, BigDecimal.ZERO);
            Result r = PayrollCalculator.calculate(in);
            // 26000/26 = 1000/day ; /8 = 125/hour ; x2 = 250 ; x10 hours = 2500
            assertThat(r.overtimeAmount()).isEqualByComparingTo("2500.00");
            assertThat(r.grossSalary()).isEqualByComparingTo("28500.00");
        }

        @Test
        @DisplayName("a multiplier with no standard day cannot value an hour")
        void needsADayLength() {
            assertThat(PayrollCalculator.overtimePay(bd("26000"), bd("26"), null, bd("2"), 600))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("Absence")
    class Absence {

        @Test
        @DisplayName("two unpaid days out of 26 costs 2/26 of basic")
        void onBasic() {
            Input in = input(bd("26000"), List.of(), bd("26"), bd("1"), bd("1"), 0,
                             BigDecimal.ZERO, null, null, "BASIC",
                             BigDecimal.ZERO, BigDecimal.ZERO);
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.absenceDeduction()).isEqualByComparingTo("2000.00");
            assertThat(r.netSalary()).isEqualByComparingTo("24000.00");
        }

        @Test
        @DisplayName("on GROSS it costs more, which is why BASIC is the default")
        void onGrossTakesMore() {
            Input in = input(bd("20000"), standardStructure(), bd("26"), bd("2"), BigDecimal.ZERO, 0,
                             BigDecimal.ZERO, null, null, "GROSS",
                             BigDecimal.ZERO, BigDecimal.ZERO);
            Result r = PayrollCalculator.calculate(in);
            // gross 29500 ; 29500/26 = 1134.6154 ; x2 = 2269.23
            assertThat(r.absenceDeduction()).isEqualByComparingTo("2269.23");
        }

        @Test
        @DisplayName("a zero working-day count does not divide by zero")
        void guardsAgainstAZeroPeriod() {
            Input in = input(bd("20000"), List.of(), BigDecimal.ZERO, bd("3"), BigDecimal.ZERO, 0,
                             BigDecimal.ZERO, null, null, "BASIC",
                             BigDecimal.ZERO, BigDecimal.ZERO);
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.absenceDeduction()).isEqualByComparingTo("0.00");
            assertThat(r.netSalary()).isEqualByComparingTo("20000.00");
        }
    }

    @Nested
    @DisplayName("Recoveries")
    class Recoveries {

        @Test
        @DisplayName("an advance and a loan both come off, in that order")
        void bothRecovered() {
            Input in = input(bd("20000"), List.of(), bd("26"), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                             BigDecimal.ZERO, null, null, "BASIC", bd("3000"), bd("2000"));
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.advanceRecovery()).isEqualByComparingTo("3000.00");
            assertThat(r.loanRecovery()).isEqualByComparingTo("2000.00");
            assertThat(r.netSalary()).isEqualByComparingTo("15000.00");
        }

        @Test
        @DisplayName("recovery stops at what the month contains; the rest stays owing")
        void cappedAtWhatIsLeft() {
            Input in = input(bd("10000"), List.of(), bd("26"), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                             BigDecimal.ZERO, null, null, "BASIC", bd("8000"), bd("8000"));
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.advanceRecovery()).isEqualByComparingTo("8000.00");
            // only 2000 was left after the advance
            assertThat(r.loanRecovery()).isEqualByComparingTo("2000.00");
            assertThat(r.netSalary()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the payslip never goes negative")
        void neverNegative() {
            Input in = input(bd("5000"), List.of(), bd("26"), bd("10"), BigDecimal.ZERO, 0,
                             BigDecimal.ZERO, null, null, "BASIC", bd("9000"), bd("9000"));
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.netSalary().signum()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Tax")
    class Tax {

        @Test
        @DisplayName("is zero, because no rate is configured anywhere in this application")
        void notConfigured() {
            Result r = PayrollCalculator.calculate(plain(bd("200000"), standardStructure()));
            assertThat(r.taxAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the taxable base is still carried, so a rate would have something to apply to")
        void taxableBaseIsTracked() {
            Result r = PayrollCalculator.calculate(plain(bd("20000"), standardStructure()));
            // basic 20000 + HRA 8000 + medical 1500, all marked taxable
            assertThat(r.taxableEarnings()).isEqualByComparingTo("29500.00");
        }
    }

    @Nested
    @DisplayName("The payslip explains itself")
    class Explanation {

        @Test
        @DisplayName("every figure has a line, and each carries the base it was taken of")
        void linesCarryTheirWorking() {
            Result r = PayrollCalculator.calculate(plain(bd("20000"), standardStructure()));

            assertThat(r.lines()).extracting(PayrollCalculator.ResultLine::code)
                    .containsExactlyInAnyOrder("BASIC", "HRA", "MED", "PF");

            PayrollCalculator.ResultLine hra = r.lines().stream()
                    .filter(l -> l.code().equals("HRA")).findFirst().orElseThrow();
            assertThat(hra.base()).isEqualByComparingTo("20000.00");
            assertThat(hra.pct()).isEqualByComparingTo("40");
            assertThat(hra.amount()).isEqualByComparingTo("8000.00");
        }

        @Test
        @DisplayName("earnings and deductions reconcile to the net")
        void reconciles() {
            Input in = input(bd("20000"), standardStructure(), bd("26"), bd("1"), BigDecimal.ZERO,
                             600, bd("1000"), bd("8"), bd("2"), "BASIC", bd("500"), bd("500"));
            Result r = PayrollCalculator.calculate(in);
            assertThat(r.grossSalary().subtract(r.deductionTotal()))
                    .isEqualByComparingTo(r.netSalary());
        }
    }
}
