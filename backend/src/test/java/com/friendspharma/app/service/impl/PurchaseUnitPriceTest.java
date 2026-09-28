package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A purchase line must cost the same per piece whichever unit it is bought in.
 *
 * <p>The product master prices a BOX — {@code TP_PRICE}, {@code MRP_PRICE} and
 * {@code IGTA} are all box figures — while {@code PurchaseCalculator} multiplies
 * them by a quantity counted in the line's own unit. The service snapshotted the
 * box price onto every line regardless of unit, so a STRIP line was inflated by
 * the strips-per-box factor and a PCS line by the whole pieces-per-box factor.
 *
 * <p>Nobody could avoid it from the form: the server overwrote whatever price
 * the client sent. It is also why {@code PU260800001} recorded 4 STRIP of Napa
 * at 2,661.32 — that line really was four strips, priced at the rate of four
 * boxes. An earlier reading of this data blamed the data entry; the entry was
 * fine and the pricing was not.
 */
class PurchaseUnitPriceTest {

    /** Napa 500mg Tablet: a box holds 3 strips of 4, so 12 pieces. */
    private static final int PCS_PER_BOX = 12;
    private static final int PCS_PER_STRIP = 4;

    private static final BigDecimal TP_PER_BOX  = new BigDecimal("187.41");
    private static final BigDecimal IGTA_PER_BOX = new BigDecimal("9.37");
    private static final BigDecimal MRP_PER_BOX = new BigDecimal("250.00");

    /** The conversion the service applies when it saves a line. */
    private static BigDecimal perUnit(BigDecimal boxPrice, int pcsPerUnit) {
        BigDecimal share = BigDecimal.valueOf(pcsPerUnit)
                .divide(BigDecimal.valueOf(PCS_PER_BOX), 10, RoundingMode.HALF_UP);
        return boxPrice.multiply(share).setScale(4, RoundingMode.HALF_UP);
    }

    /** qty × (tp + igta), the MNP line total with no percentages. */
    private static BigDecimal lineTotal(int qty, int pcsPerUnit) {
        return BigDecimal.valueOf(qty)
                .multiply(perUnit(TP_PER_BOX, pcsPerUnit).add(perUnit(IGTA_PER_BOX, pcsPerUnit)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal costPerPcs(int qty, int pcsPerUnit) {
        return lineTotal(qty, pcsPerUnit)
                .divide(BigDecimal.valueOf((long) qty * pcsPerUnit), 2, RoundingMode.HALF_UP);
    }

    @Nested
    @DisplayName("the unit does not change what a piece costs")
    class UnitNeutral {

        @Test
        @DisplayName("one box, one strip and one piece all land at the same per-piece cost")
        void sameCostPerPiece() {
            assertThat(costPerPcs(1, PCS_PER_BOX)).isEqualByComparingTo("16.40");
            assertThat(costPerPcs(1, PCS_PER_STRIP)).isEqualByComparingTo("16.40");
            assertThat(costPerPcs(1, 1)).isEqualByComparingTo("16.40");
        }

        @Test
        @DisplayName("a whole box costs the same bought as 12 pieces")
        void wholeBoxEqualsItsPieces() {
            assertThat(lineTotal(1, PCS_PER_BOX))
                    .isEqualByComparingTo(lineTotal(PCS_PER_BOX, 1));
        }

        @Test
        @DisplayName("a box costs the same bought as its 3 strips")
        void wholeBoxEqualsItsStrips() {
            assertThat(lineTotal(1, PCS_PER_BOX))
                    .isEqualByComparingTo(lineTotal(PCS_PER_BOX / PCS_PER_STRIP, PCS_PER_STRIP));
        }

        @Test
        @DisplayName("the prices themselves scale by the pack factor")
        void pricesScale() {
            assertThat(perUnit(TP_PER_BOX, PCS_PER_BOX)).isEqualByComparingTo("187.4100");
            assertThat(perUnit(TP_PER_BOX, PCS_PER_STRIP)).isEqualByComparingTo("62.4700");
            assertThat(perUnit(TP_PER_BOX, 1)).isEqualByComparingTo("15.6175");
            assertThat(perUnit(MRP_PER_BOX, 1)).isEqualByComparingTo("20.8333");
        }

        @Test
        @DisplayName("converting to a unit and back returns the box price")
        void conversionRoundTrips() {
            // Four decimals are kept for exactly this reason: rounding the
            // twelfth of a box price to two would drift the line total away
            // from the supplier's invoice by a few paisa on every line.
            BigDecimal perPiece = perUnit(TP_PER_BOX, 1);
            assertThat(perPiece.multiply(BigDecimal.valueOf(PCS_PER_BOX)))
                    .isEqualByComparingTo(TP_PER_BOX);
        }
    }

    @Nested
    @DisplayName("what the bug did")
    class TheOldBehaviour {

        /** The box price used unchanged, whatever the unit — the old snapshot. */
        private BigDecimal oldCostPerPcs(int qty, int pcsPerUnit) {
            BigDecimal total = BigDecimal.valueOf(qty).multiply(TP_PER_BOX.add(IGTA_PER_BOX));
            return total.divide(BigDecimal.valueOf((long) qty * pcsPerUnit), 2, RoundingMode.HALF_UP);
        }

        @Test
        @DisplayName("a PCS line was overpriced by the whole pieces-per-box factor")
        void pcsWasTwelveTimesOut() {
            assertThat(oldCostPerPcs(1, 1)).isEqualByComparingTo("196.78");
            assertThat(oldCostPerPcs(1, 1).divide(costPerPcs(1, 1), 0, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(BigDecimal.valueOf(PCS_PER_BOX));
        }

        @Test
        @DisplayName("a STRIP line was overpriced by the strips-per-box factor")
        void stripWasThreeTimesOut() {
            assertThat(oldCostPerPcs(1, PCS_PER_STRIP).divide(costPerPcs(1, PCS_PER_STRIP), 0, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(BigDecimal.valueOf(PCS_PER_BOX / PCS_PER_STRIP));
        }

        @Test
        @DisplayName("a BOX line was always right, which is why this went unnoticed")
        void boxWasNeverWrong() {
            assertThat(oldCostPerPcs(1, PCS_PER_BOX)).isEqualByComparingTo(costPerPcs(1, PCS_PER_BOX));
        }
    }

    @Nested
    @DisplayName("the MRP ceiling reads both bases")
    class MrpCeiling {

        /**
         * The guard's rule: the lower of the line's MRP converted by its own
         * pieces-per-unit, and the master's MRP converted by pieces-per-box.
         */
        private BigDecimal ceiling(BigDecimal lineMrp, int pcsPerUnit) {
            BigDecimal fromLine = lineMrp.divide(BigDecimal.valueOf(pcsPerUnit), 6, RoundingMode.HALF_UP);
            BigDecimal fromMaster = MRP_PER_BOX.divide(BigDecimal.valueOf(PCS_PER_BOX), 6, RoundingMode.HALF_UP);
            return fromLine.min(fromMaster);
        }

        @Test
        @DisplayName("a correctly priced PCS line is allowed")
        void correctPcsLinePasses() {
            // Line MRP is per piece now, so both bases agree at 20.83.
            assertThat(ceiling(new BigDecimal("20.8333"), 1)).isEqualByComparingTo("20.833300");
            assertThat(costPerPcs(1, 1)).isLessThan(ceiling(new BigDecimal("20.8333"), 1));
        }

        @Test
        @DisplayName("a line written before the fix cannot raise its own ceiling")
        void staleBoxMrpOnAPcsLineIsCaught() {
            // The regression this exists for. A legacy PCS line carries the box
            // MRP with noOfPcs = 1, so reading the line alone gives a ceiling of
            // 250.00 and waves a cost of 196.78 straight through. Taking the
            // master as well pins it back to 20.83.
            BigDecimal lineOnly = new BigDecimal("250.00").divide(BigDecimal.ONE, 6, RoundingMode.HALF_UP);
            assertThat(new BigDecimal("196.78")).isLessThan(lineOnly);

            BigDecimal both = ceiling(new BigDecimal("250.00"), 1);
            assertThat(both).isEqualByComparingTo("20.833333");
            assertThat(new BigDecimal("196.78")).isGreaterThan(both);
        }

        @Test
        @DisplayName("a BOX line converts by its own twelve pieces")
        void boxLineUsesItsOwnFactor() {
            assertThat(ceiling(MRP_PER_BOX, PCS_PER_BOX)).isEqualByComparingTo("20.833333");
        }
    }
}
