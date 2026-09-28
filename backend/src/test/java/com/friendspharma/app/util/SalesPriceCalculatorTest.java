package com.friendspharma.app.util;

import com.friendspharma.app.util.SalesPriceCalculator.SalesPrices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sales% → sale price, per the SRS rule
 * {@code Unit Sales Price = MRP − (MRP × Sales Percentage / 100)}.
 */
class SalesPriceCalculatorTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Nested
    @DisplayName("The SRS formula")
    class Formula {

        @ParameterizedTest(name = "MRP {0} at {1}% -> {2}")
        @CsvSource({
                "100,   0,     100.00",
                "100,   10,     90.00",
                "100,   12.5,   87.50",
                "100,   100,     0.00",
                "250,   17.4,  206.50",
                "37.75, 8.25,   34.64",
                "1,     33.333,  0.67",
        })
        void salePriceFollowsTheFormula(String mrp, String pct, String expected) {
            assertThat(SalesPriceCalculator.salePrice(bd(mrp), bd(pct)))
                    .isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("discount amount is MRP minus the sale price")
        void discountAmount() {
            assertThat(SalesPriceCalculator.discountAmount(bd("250"), bd("17.4")))
                    .isEqualByComparingTo("43.50");
        }

        @Test
        @DisplayName("results carry a scale of 2")
        void scaleIsTwo() {
            assertThat(SalesPriceCalculator.salePrice(bd("99.999"), bd("7.5")).scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("rounds half up, not half even")
        void roundsHalfUp() {
            // 1.00 at 0.5% -> 0.995 -> 1.00 under HALF_UP on the subtraction
            assertThat(SalesPriceCalculator.salePrice(bd("1.00"), bd("0.5")))
                    .isEqualByComparingTo("1.00");
            // 10 at 33.333% -> 6.6667 -> 6.67
            assertThat(SalesPriceCalculator.salePrice(bd("10"), bd("33.333")))
                    .isEqualByComparingTo("6.67");
        }
    }

    @Nested
    @DisplayName("Null percentage means the unit is not priced")
    class NotPriced {

        @Test
        @DisplayName("null percentage gives a null price, not MRP")
        void nullPctGivesNullPrice() {
            // Falling back to MRP would silently make an unpriced unit sellable
            // at full price, which the SRS forbids.
            assertThat(SalesPriceCalculator.salePrice(bd("100"), null)).isNull();
            assertThat(SalesPriceCalculator.discountAmount(bd("100"), null)).isNull();
        }

        @Test
        @DisplayName("zero percent is priced at full MRP, and is not the same as null")
        void zeroIsNotNull() {
            assertThat(SalesPriceCalculator.salePrice(bd("100"), BigDecimal.ZERO))
                    .isEqualByComparingTo("100.00");
            assertThat(SalesPriceCalculator.salePrice(bd("100"), null)).isNull();
        }

        @Test
        @DisplayName("null MRP is treated as zero")
        void nullMrp() {
            assertThat(SalesPriceCalculator.salePrice(null, bd("10")))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("Reverse: percentage for a target price")
    class Reverse {

        @Test
        @DisplayName("round-trips back to the same price")
        void roundTrips() {
            BigDecimal pct = SalesPriceCalculator.percentForPrice(bd("250"), bd("206.50"));
            assertThat(pct).isEqualByComparingTo("17.4000");
            assertThat(SalesPriceCalculator.salePrice(bd("250"), pct)).isEqualByComparingTo("206.50");
        }

        @Test
        @DisplayName("a price equal to MRP is a zero percentage")
        void noDiscount() {
            assertThat(SalesPriceCalculator.percentForPrice(bd("100"), bd("100")))
                    .isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("no percentage can express a discount off a zero MRP")
        void zeroMrpHasNoPercentage() {
            assertThat(SalesPriceCalculator.percentForPrice(BigDecimal.ZERO, bd("10"))).isNull();
        }
    }

    @Nested
    @DisplayName("All six slots together")
    class SixSlots {

        @Test
        @DisplayName("each unit and tier is priced independently")
        void independentSlots() {
            SalesPrices p = SalesPriceCalculator.calculateAll(
                    bd("200"),
                    bd("15"), bd("10"), bd("5"),      // regular:  box, strip, pcs
                    bd("20"), bd("14"), bd("8"));     // special:  box, strip, pcs

            assertThat(p.boxPrice()).isEqualByComparingTo("170.00");
            assertThat(p.stripPrice()).isEqualByComparingTo("180.00");
            assertThat(p.pcsPrice()).isEqualByComparingTo("190.00");
            assertThat(p.specialBoxPrice()).isEqualByComparingTo("160.00");
            assertThat(p.specialStripPrice()).isEqualByComparingTo("172.00");
            assertThat(p.specialPcsPrice()).isEqualByComparingTo("184.00");
        }

        @Test
        @DisplayName("a special customer never pays more than a regular one, at the rates given")
        void specialIsCheaper() {
            SalesPrices p = SalesPriceCalculator.calculateAll(
                    bd("200"), bd("15"), bd("10"), bd("5"), bd("20"), bd("14"), bd("8"));
            assertThat(p.specialBoxPrice()).isLessThan(p.boxPrice());
            assertThat(p.specialStripPrice()).isLessThan(p.stripPrice());
            assertThat(p.specialPcsPrice()).isLessThan(p.pcsPrice());
        }

        @Test
        @DisplayName("regularComplete needs all three regular units priced")
        void regularComplete() {
            assertThat(SalesPriceCalculator.calculateAll(
                    bd("100"), bd("1"), bd("2"), bd("3"), null, null, null).regularComplete()).isTrue();
            assertThat(SalesPriceCalculator.calculateAll(
                    bd("100"), bd("1"), bd("2"), null, null, null, null).regularComplete()).isFalse();
        }

        @Test
        @DisplayName("anyConfigured is false only when all six are unset")
        void anyConfigured() {
            assertThat(SalesPriceCalculator.calculateAll(
                    bd("100"), null, null, null, null, null, null).anyConfigured()).isFalse();
            // A special-only rate still counts as configured.
            assertThat(SalesPriceCalculator.calculateAll(
                    bd("100"), null, null, null, null, null, bd("5")).anyConfigured()).isTrue();
        }

        @Test
        @DisplayName("unset slots stay null while set ones price normally")
        void mixedSlots() {
            SalesPrices p = SalesPriceCalculator.calculateAll(
                    bd("100"), bd("10"), null, null, null, null, null);
            assertThat(p.boxPrice()).isEqualByComparingTo("90.00");
            assertThat(p.stripPrice()).isNull();
            assertThat(p.pcsPrice()).isNull();
        }
    }

    /**
     * Selling a product nobody has set a Sales% on.
     *
     * <p>On the Sales% screen a null percentage means "not priced" and must stay
     * distinguishable from a deliberate zero. At a till it cannot: the product is
     * on the shelf with an MRP on the box and a customer waiting. It used to
     * price at null, which removed it from the catalogue — so a newly added line
     * could not be sold until somebody went and set a discount on it.
     */
    @Nested
    @DisplayName("unitPricesForSale — an unset percentage is no discount")
    class ForSale {

        @Test
        @DisplayName("no percentages at all: every unit sells at its share of MRP")
        void nullPercentagesPriceAtMrp() {
            var p = SalesPriceCalculator.unitPricesForSale(
                    bd("250"), 100, 10, null, null, null, null, null, null);

            assertThat(p.boxPrice()).isEqualByComparingTo("250");
            assertThat(p.stripPrice()).isEqualByComparingTo("25");
            assertThat(p.pcsPrice()).isEqualByComparingTo("2.50");
            assertThat(p.specialBoxPrice()).isEqualByComparingTo("250");
            assertThat(p.specialPcsPrice()).isEqualByComparingTo("2.50");
        }

        @Test
        @DisplayName("the old behaviour, for contrast: null stays unpriced")
        void plainUnitPricesStillReturnsNull() {
            var p = SalesPriceCalculator.unitPrices(
                    bd("250"), 100, 10, null, null, null, null, null, null);

            assertThat(p.boxPrice()).as("the Sales% grid needs this to stay null").isNull();
            assertThat(p.pcsPrice()).isNull();
        }

        @Test
        @DisplayName("a percentage that is set is still applied")
        void setPercentagesAreUnaffected() {
            var p = SalesPriceCalculator.unitPricesForSale(
                    bd("250"), 100, 10, bd("30"), null, bd("9"), null, null, null);

            assertThat(p.boxPrice()).as("250 less 30%").isEqualByComparingTo("175.00");
            assertThat(p.stripPrice()).as("no strip discount set, so full share").isEqualByComparingTo("25");
            assertThat(p.pcsPrice()).as("2.50 less 9%").isEqualByComparingTo("2.28");
        }

        /**
         * The one thing that must still refuse. Without a pack size there is no
         * share of MRP to charge, and inventing one would invent a price.
         */
        @Test
        @DisplayName("missing packing still yields no price for that unit")
        void missingPackingStillNull() {
            var p = SalesPriceCalculator.unitPricesForSale(
                    bd("250"), null, null, null, null, null, null, null, null);

            assertThat(p.boxPrice()).as("a box is the MRP itself").isEqualByComparingTo("250");
            assertThat(p.stripPrice()).as("no pcs-per-strip to divide by").isNull();
            assertThat(p.pcsPrice()).as("no pcs-per-box to divide by").isNull();
        }

        @Test
        @DisplayName("an explicit zero and an absent percentage agree here")
        void zeroAndNullAgree() {
            var withNull = SalesPriceCalculator.unitPricesForSale(
                    bd("250"), 100, 10, null, null, null, null, null, null);
            var withZero = SalesPriceCalculator.unitPricesForSale(
                    bd("250"), 100, 10, bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), bd("0"));

            assertThat(withNull.boxPrice()).isEqualByComparingTo(withZero.boxPrice());
            assertThat(withNull.pcsPrice()).isEqualByComparingTo(withZero.pcsPrice());
        }

        /**
         * The hazard reading null as zero creates.
         *
         * <p>"No discount recorded" and "no MRP recorded" look the same in the
         * data — both are null — but they mean opposite things. Treating the
         * second as a zero discount would price the product at zero and sell it
         * for nothing, which is worse than the refusal it replaced.
         */
        @Test
        @DisplayName("no MRP yields no price, not a free product")
        void missingMrpIsNotFree() {
            var p = SalesPriceCalculator.unitPricesForSale(
                    null, 100, 10, null, null, null, null, null, null);

            assertThat(p.boxPrice()).isNull();
            assertThat(p.stripPrice()).isNull();
            assertThat(p.pcsPrice()).isNull();
            assertThat(p.specialPcsPrice()).isNull();
        }

        @Test
        @DisplayName("an MRP of zero is treated the same way")
        void zeroMrpIsNotFree() {
            var p = SalesPriceCalculator.unitPricesForSale(
                    new BigDecimal("0"), 100, 10, null, null, null, null, null, null);

            assertThat(p.boxPrice()).as("a product priced at nothing is a data fault").isNull();
            assertThat(p.pcsPrice()).isNull();
        }
    }
}
