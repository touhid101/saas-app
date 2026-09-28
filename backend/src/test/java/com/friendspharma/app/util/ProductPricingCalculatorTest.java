package com.friendspharma.app.util;

import com.friendspharma.app.util.ProductPricingCalculator.CalculatedPrices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product Master pricing rules.
 *
 * <p>Covers the acceptance cases from the module specification plus the
 * boundary conditions that the formulas hide: null inputs (the {@code NVL}
 * semantics), and rounding at the half-cent.
 */
class ProductPricingCalculatorTest {

    @Nested
    @DisplayName("Case 1 — the reference example")
    class ReferenceExample {

        @Test
        @DisplayName("MRP 100, conversion 25.037, VAT 17.40 -> TP 74.96, IGTA 13.04, Buy 88.00")
        void referenceCalculation() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal("25.037"), new BigDecimal("17.40"));

            // 100 - (100 * 25.037 / 100) = 74.963 -> 74.96
            assertThat(p.tpPrice()).isEqualByComparingTo("74.96");
            // 74.96 * 17.40 / 100 = 13.043 -> 13.04
            assertThat(p.igta()).isEqualByComparingTo("13.04");
            // 74.96 + 13.043 = 88.003 -> 88.00
            assertThat(p.buyPrice()).isEqualByComparingTo("88.00");
        }

        @Test
        @DisplayName("the individual steps agree with the combined pass")
        void stepsMatchCombinedPass() {
            BigDecimal mrp  = new BigDecimal("100");
            BigDecimal rate = new BigDecimal("25.037");
            BigDecimal vat  = new BigDecimal("17.40");

            BigDecimal tp   = ProductPricingCalculator.calculateTpPrice(mrp, rate);
            BigDecimal igta = ProductPricingCalculator.calculateIgta(tp, vat);
            BigDecimal buy  = ProductPricingCalculator.calculateBuyPrice(tp, vat);

            CalculatedPrices combined = ProductPricingCalculator.calculateAll(mrp, rate, vat);

            assertThat(combined.tpPrice()).isEqualByComparingTo(tp);
            assertThat(combined.igta()).isEqualByComparingTo(igta);
            assertThat(combined.buyPrice()).isEqualByComparingTo(buy);
        }
    }

    @Nested
    @DisplayName("Case 2 — zero and null inputs")
    class ZeroAndNull {

        @Test
        @DisplayName("MRP 0 collapses the whole chain to zero")
        void zeroMrpZeroesEverything() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    BigDecimal.ZERO, new BigDecimal("25.037"), new BigDecimal("17.40"));

            assertThat(p.tpPrice()).isEqualByComparingTo("0");
            assertThat(p.igta()).isEqualByComparingTo("0");
            assertThat(p.buyPrice()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("null MRP is treated as zero, matching NVL(:MRP_PRICE, 0)")
        void nullMrpBehavesAsZero() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    null, new BigDecimal("25.037"), new BigDecimal("17.40"));

            assertThat(p.mrp()).isEqualByComparingTo("0");
            assertThat(p.tpPrice()).isEqualByComparingTo("0");
            assertThat(p.igta()).isEqualByComparingTo("0");
            assertThat(p.buyPrice()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("omitted conversion rate and VAT fall back to the business defaults")
        void nullRateAndVatUseDefaults() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), null, null);

            assertThat(p.conversionRate()).isEqualByComparingTo("25.037");
            assertThat(p.vatPct()).isEqualByComparingTo("17.40");
            assertThat(p.tpPrice()).isEqualByComparingTo("74.96");
            assertThat(p.buyPrice()).isEqualByComparingTo("88.00");
        }

        @Test
        @DisplayName("zero VAT leaves buy price equal to trade price")
        void zeroVatMeansNoUplift() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal("25.037"), BigDecimal.ZERO);

            assertThat(p.igta()).isEqualByComparingTo("0");
            assertThat(p.buyPrice()).isEqualByComparingTo(p.tpPrice());
        }
    }

    @Nested
    @DisplayName("Case 3 — MRP drives every dependent value")
    class MrpChanges {

        @ParameterizedTest(name = "MRP {0} -> TP {1}, IGTA {2}, Buy {3}")
        @CsvSource({
                "100,   74.96,  13.04,  88.00",
                "200,  149.93,  26.09, 176.02",
                "50,    37.48,   6.52,  44.00",
                "12.50,  9.37,   1.63,  11.00",
                "1,      0.75,   0.13,   0.88"
        })
        void changingMrpRecalculatesTheChain(String mrp, String tp, String igta, String buy) {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal(mrp), new BigDecimal("25.037"), new BigDecimal("17.40"));

            assertThat(p.tpPrice()).isEqualByComparingTo(tp);
            assertThat(p.igta()).isEqualByComparingTo(igta);
            assertThat(p.buyPrice()).isEqualByComparingTo(buy);
        }

        @Test
        @DisplayName("raising MRP raises trade price, IGTA and buy price together")
        void allDependentsMoveWithMrp() {
            CalculatedPrices low  = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal("25.037"), new BigDecimal("17.40"));
            CalculatedPrices high = ProductPricingCalculator.calculateAll(
                    new BigDecimal("150"), new BigDecimal("25.037"), new BigDecimal("17.40"));

            assertThat(high.tpPrice()).isGreaterThan(low.tpPrice());
            assertThat(high.igta()).isGreaterThan(low.igta());
            assertThat(high.buyPrice()).isGreaterThan(low.buyPrice());
        }
    }

    @Nested
    @DisplayName("Case 4 — conversion rate drives every dependent value")
    class ConversionRateChanges {

        @ParameterizedTest(name = "rate {0}% -> TP {1}, IGTA {2}, Buy {3}")
        @CsvSource({
                "0,       100.00,  17.40, 117.40",
                "10,       90.00,  15.66, 105.66",
                "25.037,   74.96,  13.04,  88.00",
                "50,       50.00,   8.70,  58.70",
                "99,        1.00,   0.17,   1.17"
        })
        void changingConversionRateRecalculatesTheChain(String rate, String tp, String igta, String buy) {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal(rate), new BigDecimal("17.40"));

            assertThat(p.tpPrice()).isEqualByComparingTo(tp);
            assertThat(p.igta()).isEqualByComparingTo(igta);
            assertThat(p.buyPrice()).isEqualByComparingTo(buy);
        }

        @Test
        @DisplayName("a higher conversion rate lowers the trade price")
        void higherRateLowersTradePrice() {
            BigDecimal atTen    = ProductPricingCalculator.calculateTpPrice(new BigDecimal("100"), new BigDecimal("10"));
            BigDecimal atThirty = ProductPricingCalculator.calculateTpPrice(new BigDecimal("100"), new BigDecimal("30"));

            assertThat(atThirty).isLessThan(atTen);
        }
    }

    @Nested
    @DisplayName("Case 5 — VAT drives IGTA and buy price only")
    class VatChanges {

        @ParameterizedTest(name = "VAT {0}% on TP 74.96 -> IGTA {1}, Buy {2}")
        @CsvSource({
                "0,      0.00,  74.96",
                "5,      3.75,  78.71",
                "17.40, 13.04,  88.00",
                "100,   74.96, 149.92"
        })
        void changingVatRecalculatesIgtaAndBuyPrice(String vat, String igta, String buy) {
            BigDecimal tp = new BigDecimal("74.96");

            assertThat(ProductPricingCalculator.calculateIgta(tp, new BigDecimal(vat)))
                    .isEqualByComparingTo(igta);
            assertThat(ProductPricingCalculator.calculateBuyPrice(tp, new BigDecimal(vat)))
                    .isEqualByComparingTo(buy);
        }

        @Test
        @DisplayName("VAT does not affect the trade price")
        void vatLeavesTradePriceUntouched() {
            CalculatedPrices low  = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal("25.037"), new BigDecimal("5"));
            CalculatedPrices high = ProductPricingCalculator.calculateAll(
                    new BigDecimal("100"), new BigDecimal("25.037"), new BigDecimal("30"));

            assertThat(low.tpPrice()).isEqualByComparingTo(high.tpPrice());
            assertThat(high.igta()).isGreaterThan(low.igta());
            assertThat(high.buyPrice()).isGreaterThan(low.buyPrice());
        }

        @Test
        @DisplayName("buy price always equals trade price plus IGTA")
        void buyPriceIsTradePricePlusIgta() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("249.99"), new BigDecimal("18.75"), new BigDecimal("17.40"));

            // Both sides are rounded to 2dp independently, so allow the one-cent
            // difference that independent rounding can legitimately produce.
            assertThat(p.buyPrice().subtract(p.tpPrice().add(p.igta())).abs())
                    .isLessThanOrEqualTo(new BigDecimal("0.01"));
        }
    }

    @Nested
    @DisplayName("Rounding")
    class Rounding {

        @Test
        @DisplayName("results are rounded HALF_UP to 2 decimals, matching ROUND(x, 2)")
        void roundsHalfUpToTwoDecimals() {
            // 10 - (10 * 33.333 / 100) = 6.6667 -> 6.67
            assertThat(ProductPricingCalculator.calculateTpPrice(
                    new BigDecimal("10"), new BigDecimal("33.333")))
                    .isEqualByComparingTo("6.67");
        }

        @Test
        @DisplayName("an exact half rounds away from zero, not to even")
        void halfRoundsUp() {
            // 100 * 12.5 / 100 = 12.5 ... use a value that lands on .005
            // 1.00 * 0.5 / 100 = 0.005 -> 0.01 under HALF_UP (0.00 under HALF_EVEN)
            assertThat(ProductPricingCalculator.calculateIgta(
                    new BigDecimal("1.00"), new BigDecimal("0.5")))
                    .isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("every returned value carries a scale of 2")
        void scaleIsAlwaysTwo() {
            CalculatedPrices p = ProductPricingCalculator.calculateAll(
                    new BigDecimal("99.999"), new BigDecimal("7.5"), new BigDecimal("17.40"));

            assertThat(p.tpPrice().scale()).isEqualTo(2);
            assertThat(p.igta().scale()).isEqualTo(2);
            assertThat(p.buyPrice().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Business defaults")
    class Defaults {

        @Test
        void conversionRateDefaultIs25Point037() {
            assertThat(ProductPricingCalculator.DEFAULT_CONVERSION_RATE).isEqualByComparingTo("25.037");
        }

        @Test
        void vatDefaultIs17Point40() {
            assertThat(ProductPricingCalculator.DEFAULT_VAT_PCT).isEqualByComparingTo("17.40");
        }
    }
}
