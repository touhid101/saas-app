package com.friendspharma.app.service.impl;

import com.friendspharma.app.service.impl.SalesPercentServiceImpl.Priced;
import com.friendspharma.app.util.SalesPriceCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "at or below cost" filter on the Sales% grid.
 *
 * <p>It answers one question — is this line being sold for less than it was
 * bought for — and the answer decides whether somebody is told they are losing
 * money on a product. Worth pinning: the comparison has to survive the MRP being
 * apportioned per unit, which is where the arithmetic is easy to get wrong.
 */
class BelowCostFilterTest {

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    /** As the service builds it: prices from the calculator, costs scaled per unit. */
    private static Priced priced(String mrp, Integer perBox, Integer perStrip,
                                 String boxPct, String stripPct, String pcsPct,
                                 String costPerPcs) {
        SalesPriceCalculator.UnitPrices prices = SalesPriceCalculator.unitPrices(
                bd(mrp), perBox, perStrip,
                boxPct   == null ? null : bd(boxPct),
                stripPct == null ? null : bd(stripPct),
                pcsPct   == null ? null : bd(pcsPct),
                null, null, null);

        BigDecimal cPcs = costPerPcs == null ? null : bd(costPerPcs);
        BigDecimal cStrip = (cPcs == null || perStrip == null) ? null
                : cPcs.multiply(BigDecimal.valueOf(perStrip)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal cBox = (cPcs == null || perBox == null) ? null
                : cPcs.multiply(BigDecimal.valueOf(perBox)).setScale(2, java.math.RoundingMode.HALF_UP);

        return new Priced(prices, cBox, cStrip, cPcs);
    }

    @Nested
    @DisplayName("Napa 500mg Tablet (Updated) at Prescription Aid — the reported case")
    class ReportedCase {

        /* Straight from the database: cost 1.7815 per piece, 100 pieces to a box,
           so the grid's Buy Price column shows 178.15 for the box. MRP 250. */
        private static final String MRP  = "250";
        private static final String COST = "1.7815";
        private static final int    PER_BOX = 100;

        @Test
        @DisplayName("30% off a ৳250 box gives ৳175.00 against a ৳178.15 buy price — caught")
        void thirtyPercentIsBelowCost() {
            Priced p = priced(MRP, PER_BOX, null, "30", null, null, COST);

            assertThat(p.costBox())
                    .as("the Buy Price the grid shows for a box")
                    .isEqualByComparingTo("178.15");
            assertThat(p.prices().boxPrice())
                    .as("250 less 30%")
                    .isEqualByComparingTo("175.00");
            assertThat(p.anyBelowCost())
                    .as("175.00 is under the 178.15 it cost — this is the line to find")
                    .isTrue();
        }

        @Test
        @DisplayName("28% gives ৳180.00 and is left alone")
        void justAboveCostIsNotCaught() {
            Priced p = priced(MRP, PER_BOX, null, "28", null, null, COST);
            assertThat(p.prices().boxPrice()).isEqualByComparingTo("180.00");
            assertThat(p.anyBelowCost()).isFalse();
        }

        /** At or below, not merely below: breaking even is not a margin. */
        @Test
        @DisplayName("selling at exactly cost counts as below")
        void breakEvenCounts() {
            Priced p = priced("178.15", PER_BOX, null, "0", null, null, "1.7815");
            assertThat(p.prices().boxPrice()).isEqualByComparingTo("178.15");
            assertThat(p.anyBelowCost()).isTrue();
        }
    }

    @Nested
    @DisplayName("the apportionment the comparison depends on")
    class Apportionment {

        /**
         * The bug this guards. Pricing a single tablet off the box MRP put one
         * tablet of a ৳400 box at ৳364 — which would clear any per-piece cost
         * and hide every loss-making pcs line.
         */
        @Test
        @DisplayName("a piece is priced off its share of MRP, not the whole box")
        void pieceUsesItsShareOfMrp() {
            Priced p = priced("400", 100, null, null, null, "9", "3.00");
            assertThat(p.prices().pcsPrice())
                    .as("400/100 = 4.00 a piece, less 9%")
                    .isEqualByComparingTo("3.64");
            assertThat(p.anyBelowCost())
                    .as("3.64 clears the 3.00 it cost")
                    .isFalse();
        }

        @Test
        @DisplayName("the same product on pricier stock is caught")
        void sameProductDearerStock() {
            Priced p = priced("400", 100, null, null, null, "9", "3.70");
            assertThat(p.anyBelowCost())
                    .as("3.64 no longer clears 3.70")
                    .isTrue();
        }

        @Test
        @DisplayName("one bad unit is enough — a strip below cost flags the row")
        void anyUnitFlagsTheRow() {
            //  Box  : 250 less 5%  = 237.50 vs 178.15  → fine
            //  Strip: 25  less 40% =  15.00 vs  17.82  → below
            Priced p = priced("250", 100, 10, "5", "40", null, "1.7815");
            assertThat(p.prices().boxPrice()).isEqualByComparingTo("237.50");
            assertThat(p.anyBelowCost()).isTrue();
        }
    }

    @Nested
    @DisplayName("what is deliberately not caught")
    class NotCaught {

        @Test
        @DisplayName("a product with no stock has no known cost, so no known loss")
        void unknownCostIsNotALoss() {
            Priced p = priced("250", 100, null, "90", null, null, null);
            assertThat(p.anyBelowCost())
                    .as("90% off looks alarming, but nothing says what it cost")
                    .isFalse();
        }

        @Test
        @DisplayName("an unpriced unit is not compared at all")
        void unpricedUnitIsSkipped() {
            Priced p = priced("250", 100, null, null, null, null, "1.7815");
            assertThat(p.prices().boxPrice()).isNull();
            assertThat(p.anyBelowCost()).isFalse();
        }
    }
}
