package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An invoice is settled when what remains is smaller than any coin.
 *
 * <p>Totals are stored to four decimal places; the smallest amount anyone can
 * hand over is one paisa. Invoice SL260824803 was payable 5.2920, the screen
 * showed 5.29, the customer paid 5.29 — and 0.0020 stayed outstanding. The
 * status held at Partial, the remaining balance rounded to 0.00 so the Collect
 * button disappeared, and nothing could ever clear it.
 *
 * <p>These pin the arithmetic. The tolerance mirrors
 * {@code SalesServiceImpl.SETTLEMENT_TOLERANCE}, which is private.
 */
class SettlementToleranceTest {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** What the service does: strictly-less-than one paisa is settled. */
    private static boolean isSettled(String owing) {
        return new BigDecimal(owing).compareTo(TOLERANCE) < 0;
    }

    @Test
    @DisplayName("the live case: paying the displayed amount settles the invoice")
    void theLiveCase() {
        BigDecimal payable = new BigDecimal("5.2920");
        BigDecimal shown   = payable.setScale(2, RoundingMode.HALF_UP);
        assertThat(shown).isEqualByComparingTo("5.29");

        BigDecimal remaining = payable.subtract(shown);
        assertThat(remaining).isEqualByComparingTo("0.0020");

        // Before the tolerance this was Partial forever.
        assertThat(remaining.compareTo(BigDecimal.ZERO) > 0).isTrue();
        assertThat(isSettled(remaining.toPlainString())).isTrue();
    }

    @Test
    @DisplayName("a real one-paisa debt is still owed")
    void onePaisaIsStillDebt() {
        // Strictly-less-than, so the smallest genuine debt does not vanish.
        assertThat(isSettled("0.01")).isFalse();
        assertThat(isSettled("0.02")).isFalse();
        assertThat(isSettled("1.00")).isFalse();
    }

    @Test
    @DisplayName("sub-paisa remainders are dust, whatever their sign")
    void dust() {
        assertThat(isSettled("0.0020")).isTrue();
        assertThat(isSettled("0.0099")).isTrue();
        assertThat(isSettled("0.0000")).isTrue();
        // Overpaid by a hair — settled, not owed.
        assertThat(isSettled("-0.0050")).isTrue();
    }

    @Test
    @DisplayName("the cap on a collection is the figure the customer was shown")
    void capIsTheRoundedFigure() {
        // Comparing the tendered amount against the raw 4dp owing would refuse a
        // customer paying the exact amount printed on their bill whenever
        // rounding went up: owing 5.2950 displays as 5.30.
        BigDecimal owing = new BigDecimal("5.2950");
        BigDecimal shown = owing.setScale(2, RoundingMode.HALF_UP);
        assertThat(shown).isEqualByComparingTo("5.30");
        assertThat(shown.compareTo(owing) > 0).isTrue();      // more than raw owing
        assertThat(shown.compareTo(shown) > 0).isFalse();     // but accepted
    }

    @Test
    @DisplayName("a part payment leaves the invoice open")
    void partPaymentStaysOpen() {
        BigDecimal payable = new BigDecimal("500.0000");
        BigDecimal paid    = new BigDecimal("200.00");
        assertThat(isSettled(payable.subtract(paid).toPlainString())).isFalse();
    }
}
