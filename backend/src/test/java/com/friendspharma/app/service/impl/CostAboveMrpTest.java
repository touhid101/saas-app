package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock cannot be received above the price it may be sold at.
 *
 * <p>MRP is the maximum a pharmacy may legally charge, so a landed cost above it
 * guarantees a loss on every piece. In practice it never means that — it means
 * the unit and the price disagree, and the packing factor has multiplied the
 * per-piece cost.
 *
 * <p>The live case: purchase PU260800001 recorded 4 STRIP of Napa at 2,661.32,
 * which is 665.33 per strip against a strip MRP of 75.60. Nothing downstream
 * could detect it: the cost froze onto the lot, and a single sale from that lot
 * pushed one day's gross profit to −2,096.
 *
 * <p><b>The cause was not the data entry.</b> That line really was four strips.
 * The service priced every line off the product master, which quotes a BOX, so
 * the strips were charged at four boxes — see {@link PurchaseUnitPriceTest}.
 * The arithmetic below still holds and the guard is still worth having; what it
 * catches is a pricing fault upstream, not a typo at the counter.
 */
class CostAboveMrpTest {

    /** What the posting service computes, and what it compares against. */
    private static BigDecimal costPerPcs(String lineTotal, String discountPct, int qty, int pcsPerUnit) {
        BigDecimal net = new BigDecimal(lineTotal)
                .multiply(BigDecimal.ONE.subtract(new BigDecimal(discountPct).movePointLeft(2)));
        return net.divide(BigDecimal.valueOf((long) qty * pcsPerUnit), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal mrpPerPcs(String mrp, int pcsPerBox) {
        return new BigDecimal(mrp).divide(BigDecimal.valueOf(pcsPerBox), 6, RoundingMode.HALF_UP);
    }

    private static boolean refused(BigDecimal cost, BigDecimal mrpPerPcs) {
        return cost.compareTo(mrpPerPcs) > 0;
    }

    @Nested
    @DisplayName("the live case")
    class LiveCase {

        // Napa: MRP 756 per box of 100, 10 pieces per strip.
        private final BigDecimal mrpPcs = mrpPerPcs("756", 100);

        @Test
        @DisplayName("4 strips at a box price is refused")
        void wrongUnitIsRefused() {
            // 4 × 10 = 40 pieces, so 2,395.19 net over 40.
            BigDecimal asStrip = costPerPcs("2661.32", "10", 4, 10);
            assertThat(asStrip).isEqualByComparingTo("59.879700");
            assertThat(mrpPcs).isEqualByComparingTo("7.560000");
            assertThat(refused(asStrip, mrpPcs)).isTrue();
        }

        @Test
        @DisplayName("the same money spread over 4 boxes worth of pieces passes")
        void rightUnitPasses() {
            // 4 × 100 = 400 pieces — 5.99 each, 79% of MRP. An ordinary trade price.
            BigDecimal asBox = costPerPcs("2661.32", "10", 4, 100);
            assertThat(asBox).isEqualByComparingTo("5.987970");
            assertThat(refused(asBox, mrpPcs)).isFalse();
        }

        @Test
        @DisplayName("the error is exactly the packing factor")
        void factorIsThePacking() {
            // Ten pieces per strip, so entering strips instead of boxes inflates
            // the per-piece cost tenfold. That is the signature to look for.
            BigDecimal asStrip = costPerPcs("2661.32", "10", 4, 10);
            BigDecimal asBox   = costPerPcs("2661.32", "10", 4, 100);
            assertThat(asStrip.divide(asBox, 0, RoundingMode.HALF_UP)).isEqualByComparingTo("10");
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("buying exactly at MRP is allowed — zero margin, not an error")
        void atMrpPasses() {
            BigDecimal mrpPcs = mrpPerPcs("100", 10);            // 10.00
            assertThat(refused(new BigDecimal("10.000000"), mrpPcs)).isFalse();
        }

        @Test
        @DisplayName("a single paisa above MRP is refused")
        void justAboveIsRefused() {
            BigDecimal mrpPcs = mrpPerPcs("100", 10);
            assertThat(refused(new BigDecimal("10.010000"), mrpPcs)).isTrue();
        }

        @Test
        @DisplayName("an invoice discount is applied before the comparison")
        void discountCountsFirst() {
            // 110 gross over 10 pieces is 11.00 — above a 10.00 MRP. With 20% off
            // it lands at 8.80 and is fine. Comparing the gross would refuse a
            // legitimate receipt.
            BigDecimal mrpPcs = mrpPerPcs("100", 10);
            assertThat(refused(costPerPcs("110", "0",  1, 10), mrpPcs)).isTrue();
            assertThat(refused(costPerPcs("110", "20", 1, 10), mrpPcs)).isFalse();
        }
    }
}
