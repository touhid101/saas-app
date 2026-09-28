package com.friendspharma.app.util;

import com.friendspharma.app.util.PurchaseCalculator.InvoiceTotals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purchase line and invoice arithmetic.
 *
 * <p>The expected values are computed independently from the APEX expression
 * rather than copied from the implementation, so a change in the code that
 * alters a total shows up as a failure here.
 */
class PurchaseCalculatorTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Nested
    @DisplayName("MNP basis — qty x (tp + igta) - (qty x tp x tpPct/100)")
    class MnpBasis {

        @Test
        @DisplayName("no discounts: 10 x (50 + 8) - (10 x 50 x 10%) = 530.00")
        void grossOnly() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), BigDecimal.ZERO, BigDecimal.ZERO);
            // 10*58 = 580 ; 10*50*0.10 = 50 ; 580 - 50 = 530
            assertThat(total).isEqualByComparingTo("530.00");
        }

        @Test
        @DisplayName("percentage discount applies to the rounded base")
        void percentageDiscount() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), bd("5"), BigDecimal.ZERO);
            // base 530 ; 530 - 5% = 503.50
            assertThat(total).isEqualByComparingTo("503.50");
        }

        @Test
        @DisplayName("flat discount is per unit and applies AFTER the final rounding")
        void flatDiscountIsPerUnitAndOutsideRounding() {
            // This is the asymmetry with the MRP branch, reproduced from the
            // original expression: qty x flat is subtracted after ROUND(...).
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), bd("5"), bd("2"));
            // 503.50 - (10 x 2) = 483.50
            assertThat(total).isEqualByComparingTo("483.50");
        }

        @Test
        @DisplayName("zero IGTA and zero TP% reduce to qty x tp")
        void degeneratesToQtyTimesTp() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("7"), bd("12.50"), BigDecimal.ZERO, bd("20"),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(total).isEqualByComparingTo("87.50");
        }

        @Test
        @DisplayName("nulls are treated as zero, matching NVL")
        void nullsBehaveAsZero() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("5"), bd("10"), null, null, null, null, null);
            assertThat(total).isEqualByComparingTo("50.00");
        }

        @ParameterizedTest(name = "qty {0}, tp {1}, igta {2}, tpPct {3}, disc {4}, flat {5} -> {6}")
        @CsvSource({
                "1,   100, 0,  0,  0,  0,  100.00",
                "1,   100, 20, 0,  0,  0,  120.00",
                "1,   100, 20, 25, 0,  0,   95.00",
                "2,   100, 20, 25, 10, 0,  171.00",
                "2,   100, 20, 25, 10, 5,  161.00",
                // 100 x 4.40 = 440 ; tpCut 45 ; base 395 ; less 7.5% = 365.375 -> 365.38
                // ; less 100 x 0.25 = 340.38. Exercises HALF_UP at the half-cent.
                "100, 3.75, 0.65, 12, 7.5, 0.25, 340.38",
        })
        void tableOfCases(String qty, String tp, String igta, String tpPct,
                          String disc, String flat, String expected) {
            assertThat(PurchaseCalculator.calculateLineTotal(
                    "MNP", bd(qty), bd(tp), bd(igta), bd("999"), bd(tpPct), bd(disc), bd(flat)))
                    .isEqualByComparingTo(expected);
        }
    }

    @Nested
    @DisplayName("MRP basis — qty x (mrp - (mrp x disc/100 + flat))")
    class MrpBasis {

        @Test
        @DisplayName("no discounts: 10 x 70 = 700.00")
        void grossOnly() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(total).isEqualByComparingTo("700.00");
        }

        @Test
        @DisplayName("percentage discount applies per unit")
        void percentageDiscount() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), bd("10"), BigDecimal.ZERO);
            // 70 - 7 = 63 ; x10 = 630
            assertThat(total).isEqualByComparingTo("630.00");
        }

        @Test
        @DisplayName("flat discount is per unit and applies INSIDE the rounding")
        void flatDiscountIsPerUnitInsideRounding() {
            BigDecimal total = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("10"), bd("50"), bd("8"), bd("70"),
                    bd("10"), bd("10"), bd("3"));
            // 70 - (7 + 3) = 60 ; x10 = 600
            assertThat(total).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("MRP ignores TP price, IGTA and TP% entirely")
        void ignoresMnpInputs() {
            BigDecimal withMnpInputs = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("4"), bd("999"), bd("999"), bd("25"), bd("99"), bd("5"), bd("1"));
            BigDecimal withoutThem = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("4"), BigDecimal.ZERO, BigDecimal.ZERO, bd("25"),
                    BigDecimal.ZERO, bd("5"), bd("1"));
            assertThat(withMnpInputs).isEqualByComparingTo(withoutThem);
        }
    }

    @Nested
    @DisplayName("The two bases genuinely differ")
    class BasesDiffer {

        @Test
        @DisplayName("same inputs give different totals under MNP and MRP")
        void differentResults() {
            BigDecimal mnp = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"), bd("5"), bd("2"));
            BigDecimal mrp = PurchaseCalculator.calculateLineTotal(
                    "MRP", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"), bd("5"), bd("2"));
            assertThat(mnp).isNotEqualByComparingTo(mrp);
        }

        @Test
        @DisplayName("an unknown basis falls back to MNP rather than returning zero")
        void unknownTypeFallsBackToMnp() {
            BigDecimal unknown = PurchaseCalculator.calculateLineTotal(
                    "SOMETHING", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"), bd("5"), bd("2"));
            BigDecimal mnp = PurchaseCalculator.calculateLineTotal(
                    "MNP", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"), bd("5"), bd("2"));
            assertThat(unknown).isEqualByComparingTo(mnp);
        }
    }

    @Nested
    @DisplayName("Invoice roll-up")
    class InvoiceRollUp {

        @Test
        @DisplayName("discount applies to the line sum, then tax and service charge are added")
        void ordering() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(bd("1000"), bd("1200"), bd("10"), bd("50"), bd("25"), BigDecimal.ZERO, bd("600"));

            assertThat(t.totalAmount()).isEqualByComparingTo("1000.00");
            assertThat(t.invoiceDisAmt()).isEqualByComparingTo("100.00");
            // 1000 - 100 + 50 + 25
            assertThat(t.amountToBePaid()).isEqualByComparingTo("975.00");
            assertThat(t.dueAmount()).isEqualByComparingTo("375.00");
        }

        @Test
        @DisplayName("total discount combines the line giveaway and the invoice cut")
        void totalDiscountCombinesBoth() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(bd("1000"), bd("1200"), bd("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            // 200 given away on the lines + 100 on the invoice
            assertThat(t.totalDiscount()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("[V68] cashback comes off the payable, after tax and service charge")
        void cashbackReducesPayable() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(
                    bd("1000"), bd("1000"), BigDecimal.ZERO, bd("50"), bd("25"), bd("200"), BigDecimal.ZERO);

            // 1000 + 50 tax + 25 service = 1075, less 200 back
            assertThat(t.amountToBePaid()).isEqualByComparingTo("875.00");
            assertThat(t.cashbackAmount()).isEqualByComparingTo("200.00");
            assertThat(t.dueAmount()).isEqualByComparingTo("875.00");
        }

        @Test
        @DisplayName("[V68] cashback is not a discount and is not counted as one")
        void cashbackIsNotDiscount() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(
                    bd("1000"), bd("1200"), bd("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                    bd("100"), BigDecimal.ZERO);

            // 200 given away on the lines + 100 off the invoice. The 100 cashback
            // is an incentive the supplier paid, not a price they quoted, and
            // folding it in here would overstate what was discounted.
            assertThat(t.totalDiscount()).isEqualByComparingTo("300.00");
            assertThat(t.cashbackAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("paying in full leaves nothing due")
        void fullyPaid() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(bd("500"), bd("500"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, bd("500"));
            assertThat(t.dueAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("overpaying gives a negative due")
        void overpaid() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(bd("500"), bd("500"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, bd("600"));
            assertThat(t.dueAmount()).isEqualByComparingTo("-100.00");
        }

        @Test
        @DisplayName("nulls throughout produce zeroes, not a crash")
        void allNulls() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(null, null, null, null, null, BigDecimal.ZERO, null);
            assertThat(t.totalAmount()).isEqualByComparingTo("0.00");
            assertThat(t.amountToBePaid()).isEqualByComparingTo("0.00");
            assertThat(t.dueAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("every figure is scaled to 2 decimals")
        void scaleIsTwo() {
            InvoiceTotals t = PurchaseCalculator.calculateInvoice(bd("333.333"), bd("400"), bd("7.5"), bd("1.005"), bd("0.001"), BigDecimal.ZERO, bd("10"));
            assertThat(t.totalAmount().scale()).isEqualTo(2);
            assertThat(t.invoiceDisAmt().scale()).isEqualTo(2);
            assertThat(t.amountToBePaid().scale()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("line gross is list price — TP% is a discount, not part of it")
    void lineGrossIgnoresEveryDiscount() {
        /* This test used to compare gross against the total with tpPct still
           applied, which pinned the bug: the trade-price cut counted as part
           of gross and so never reached TOTAL_DISCOUNT. On PU260900007 a 3%
           TP cut worth 5.62 disappeared from the reported discount.

           Gross is the undiscounted line — 10 × (50 + 8) = 580. The 10% TP cut
           of 50 is a reduction like any other and belongs in the discount. */
        BigDecimal gross = PurchaseCalculator.calculateLineGross(
                "MNP", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"));
        assertThat(gross).isEqualByComparingTo(bd("580.00"));

        BigDecimal withTpCut = PurchaseCalculator.calculateLineTotal(
                "MNP", bd("10"), bd("50"), bd("8"), bd("70"), bd("10"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(withTpCut).isEqualByComparingTo(bd("530.00"));
        assertThat(gross.subtract(withTpCut)).isEqualByComparingTo(bd("50.00"));
    }

    @Test
    @DisplayName("total discount is TP% plus the line discount plus the invoice cut")
    void totalDiscountCountsEveryReduction() {
        // PU260900007: one unit at 187.41, TP% 3, Disc% 2, invoice 10%.
        BigDecimal gross = PurchaseCalculator.calculateLineGross(
                "MNP", bd("1"), bd("187.41"), BigDecimal.ZERO, BigDecimal.ZERO, bd("3"));
        BigDecimal total = PurchaseCalculator.calculateLineTotal(
                "MNP", bd("1"), bd("187.41"), BigDecimal.ZERO, BigDecimal.ZERO, bd("3"),
                bd("2"), BigDecimal.ZERO);

        InvoiceTotals t = PurchaseCalculator.calculateInvoice(total, gross, bd("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(total).isEqualByComparingTo(bd("178.15"));
        assertThat(t.invoiceDisAmt()).isEqualByComparingTo(bd("17.82"));
        // 5.62 (TP%) + 3.64 (Disc%) + 17.82 (invoice) — was 21.46 with TP% lost.
        assertThat(t.totalDiscount()).isEqualByComparingTo(bd("27.08"));
        // The reported discount must not change what is owed.
        assertThat(t.amountToBePaid()).isEqualByComparingTo(bd("160.33"));
    }
}
