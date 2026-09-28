package com.friendspharma.app.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * BOX / STRIP / PCS conversion.
 *
 * <p>These are the sums every stock figure in the system is built from. The
 * legacy Oracle view got them wrong in a way nobody noticed for years — it
 * assumed a purchase was always in boxes — so they are pinned here explicitly.
 */
class StockUnitConverterTest {

    // A product packed 12 strips to a box, 4 pieces to a strip: 48 pcs per box.
    private static final Integer PCS_PER_BOX   = 48;
    private static final Integer PCS_PER_STRIP = 4;

    @Nested
    @DisplayName("factorFor")
    class Factors {

        @Test
        void resolvesEachUnit() {
            assertThat(StockUnitConverter.factorFor("BOX", PCS_PER_BOX, PCS_PER_STRIP)).isEqualTo(48);
            assertThat(StockUnitConverter.factorFor("STRIP", PCS_PER_BOX, PCS_PER_STRIP)).isEqualTo(4);
            assertThat(StockUnitConverter.factorFor("PCS", PCS_PER_BOX, PCS_PER_STRIP)).isEqualTo(1);
        }

        @Test
        void isCaseAndWhitespaceInsensitive() {
            assertThat(StockUnitConverter.factorFor(" box ", PCS_PER_BOX, PCS_PER_STRIP)).isEqualTo(48);
        }

        @Test
        @DisplayName("returns null rather than 1 when the pack size is unknown")
        void unknownPackingIsNullNotOne() {
            // The distinction that matters: defaulting an unconfigured box to 1
            // would turn a 48-piece box into a single tablet, and the error would
            // surface as a stock discrepancy months later rather than at entry.
            assertThat(StockUnitConverter.factorFor("BOX", null, PCS_PER_STRIP)).isNull();
            assertThat(StockUnitConverter.factorFor("STRIP", PCS_PER_BOX, null)).isNull();
            assertThat(StockUnitConverter.factorFor("BOX", 0, PCS_PER_STRIP)).isNull();
        }

        @Test
        void pcsNeedsNoPacking() {
            assertThat(StockUnitConverter.factorFor("PCS", null, null)).isEqualTo(1);
        }

        @Test
        void unknownUnitIsNull() {
            assertThat(StockUnitConverter.factorFor("CARTON", PCS_PER_BOX, PCS_PER_STRIP)).isNull();
        }
    }

    @Nested
    @DisplayName("toPcs")
    class ToPieces {

        @Test
        void convertsEveryUnit() {
            assertThat(StockUnitConverter.toPcs(new BigDecimal("10"), "BOX", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("480");
            assertThat(StockUnitConverter.toPcs(new BigDecimal("10"), "STRIP", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("40");
            assertThat(StockUnitConverter.toPcs(new BigDecimal("10"), "PCS", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("matches the live purchase data the migration converted")
        void matchesBackfilledRows() {
            // Straight from POS_TRANSACTION_DTL: these three rows are what the
            // V9 backfill turned into batch stock, and the numbers must agree.
            assertThat(StockUnitConverter.toPcs(new BigDecimal("10"), "BOX", 12, 4))
                    .isEqualByComparingTo("120");
            assertThat(StockUnitConverter.toPcs(new BigDecimal("4"), "STRIP", 100, 10))
                    .isEqualByComparingTo("40");
            assertThat(StockUnitConverter.toPcs(new BigDecimal("30"), "BOX", 50, 10))
                    .isEqualByComparingTo("1500");
        }

        @Test
        void refusesAnUnconfiguredUnit() {
            assertThatThrownBy(() ->
                    StockUnitConverter.toPcs(new BigDecimal("1"), "BOX", null, PCS_PER_STRIP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No pack size configured");
        }

        @Test
        void nullQuantityIsZero() {
            assertThat(StockUnitConverter.toPcs(null, "BOX", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("fromPcs")
    class FromPieces {

        @Test
        void expressesPiecesInLargerUnits() {
            assertThat(StockUnitConverter.fromPcs(new BigDecimal("480"), "BOX", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("10");
            assertThat(StockUnitConverter.fromPcs(new BigDecimal("40"), "STRIP", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("rounds for display without the balance ever rounding")
        void partialUnitsRound() {
            // 50 pcs is 1.04 boxes. The display rounds; the stored balance stays
            // 50 pieces exactly, which is why pieces are the only stored unit.
            assertThat(StockUnitConverter.fromPcs(new BigDecimal("50"), "BOX", PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualByComparingTo("1.04");
        }

        @Test
        void unconfiguredUnitIsNull() {
            assertThat(StockUnitConverter.fromPcs(new BigDecimal("50"), "BOX", null, null)).isNull();
        }
    }

    @Nested
    @DisplayName("breakdown and describe")
    class Breakdown {

        @Test
        void splitsIntoWholeBoxesStripsAndPieces() {
            // 100 pcs = 2 boxes (96) + 1 strip (4) + 0 loose
            var b = StockUnitConverter.breakdown(new BigDecimal("100"), PCS_PER_BOX, PCS_PER_STRIP);
            assertThat(b.boxes()).isEqualTo(2);
            assertThat(b.strips()).isEqualTo(1);
            assertThat(b.pcs()).isEqualByComparingTo("0");
        }

        @Test
        void keepsTheRemainderAsLoosePieces() {
            // 103 pcs = 2 boxes (96) + 1 strip (4) + 3 loose
            var b = StockUnitConverter.breakdown(new BigDecimal("103"), PCS_PER_BOX, PCS_PER_STRIP);
            assertThat(b.boxes()).isEqualTo(2);
            assertThat(b.strips()).isEqualTo(1);
            assertThat(b.pcs()).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("reads like a shelf label")
        void describesReadably() {
            assertThat(StockUnitConverter.describe(new BigDecimal("103"), PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualTo("2 box 1 strip 3 pcs");
            assertThat(StockUnitConverter.describe(new BigDecimal("96"), PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualTo("2 box");
            assertThat(StockUnitConverter.describe(new BigDecimal("3"), PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualTo("3 pcs");
        }

        @Test
        void zeroStockSaysSo() {
            assertThat(StockUnitConverter.describe(BigDecimal.ZERO, PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualTo("0 pcs");
            assertThat(StockUnitConverter.describe(null, PCS_PER_BOX, PCS_PER_STRIP))
                    .isEqualTo("0 pcs");
        }

        @Test
        @DisplayName("falls back to pieces when the product has no packing")
        void unpackedProductShowsPieces() {
            // Product 46 in the live data has no box or leaf configured.
            assertThat(StockUnitConverter.describe(new BigDecimal("10"), 0, 0))
                    .isEqualTo("10 pcs");
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("converting out and back loses nothing on whole units")
        void isLossless() {
            for (String unit : new String[]{"BOX", "STRIP", "PCS"}) {
                BigDecimal qty = new BigDecimal("7");
                BigDecimal pcs = StockUnitConverter.toPcs(qty, unit, PCS_PER_BOX, PCS_PER_STRIP);
                assertThat(StockUnitConverter.fromPcs(pcs, unit, PCS_PER_BOX, PCS_PER_STRIP))
                        .as("round trip through %s", unit)
                        .isEqualByComparingTo(qty);
            }
        }
    }

    @Nested
    @DisplayName("normalise")
    class Normalise {

        @Test
        void blankAndNullBothMeanPieces() {
            assertThat(StockUnitConverter.normalise(null)).isEqualTo("PCS");
            assertThat(StockUnitConverter.normalise("  ")).isEqualTo("PCS");
        }

        @Test
        void recognisesTheThreeStockUnits() {
            assertThat(StockUnitConverter.isKnownUnit("box")).isTrue();
            assertThat(StockUnitConverter.isKnownUnit("STRIP")).isTrue();
            assertThat(StockUnitConverter.isKnownUnit("Pcs")).isTrue();
            assertThat(StockUnitConverter.isKnownUnit("CARTON")).isFalse();
        }
    }
}
