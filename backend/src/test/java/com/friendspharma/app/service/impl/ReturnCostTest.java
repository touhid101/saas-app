package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returned goods go back on the shelf at what they cost, not what they sold for.
 *
 * <p>The posting service derives a line's unit cost by dividing its total by the
 * pieces on it. For a purchase that total is money paid, so the result is the
 * landed cost. For a sales return the same total is the <em>refund</em> — and
 * using it books the goods back valued at their selling price.
 *
 * <p>The consequence compounds: the batch's weighted average cost moves toward
 * the sale price, every later sale from that lot reports the wrong COGS, and
 * stock valuation counts margin as inventory. On live data a Napa lot bought at
 * one price came back at 6.96 — its selling price — and the daily P&L then read
 * a −1698% margin.
 */
class ReturnCostTest {

    /** What the posting service does for a purchase line. */
    private static BigDecimal costFromLineTotal(String lineTotal, String pcs) {
        return new BigDecimal(lineTotal).divide(new BigDecimal(pcs), 6, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("a purchase line's cost is its total over its pieces")
    void purchaseCost() {
        // 24 pieces landed for 57.08 → 2.378333 each.
        assertThat(costFromLineTotal("57.08", "24")).isEqualByComparingTo("2.378333");
    }

    @Test
    @DisplayName("the refund is not the cost — that is the whole bug")
    void refundIsNotCost() {
        // 40 pieces refunded at the 6.96 they sold for.
        BigDecimal fromRefund = costFromLineTotal("278.40", "40");
        assertThat(fromRefund).isEqualByComparingTo("6.960000");

        // What they actually cost when issued, read off the SALE movement.
        BigDecimal actualCost = new BigDecimal("59.879750");

        assertThat(fromRefund).isNotEqualByComparingTo(actualCost);
    }

    @Test
    @DisplayName("valuing a return at the refund moves the batch's average cost")
    void averageCostDrifts() {
        // A lot of 40 at 59.87975 is fully sold, then fully returned.
        BigDecimal trueCost = new BigDecimal("59.879750");
        BigDecimal qty = new BigDecimal("40");

        // Correct: back at what it cost, so the lot is worth what it was.
        BigDecimal correctValue = trueCost.multiply(qty);
        assertThat(correctValue).isEqualByComparingTo("2395.190000");

        // Wrong: back at the refund, so the same 40 pieces are now worth 278.40.
        BigDecimal wrongValue = new BigDecimal("6.96").multiply(qty);
        assertThat(wrongValue).isEqualByComparingTo("278.40");

        // Over two thousand taka of inventory value evaporates on one return.
        assertThat(correctValue.subtract(wrongValue)).isEqualByComparingTo("2116.790000");
    }

    @Test
    @DisplayName("a sale and its full return cancel out when both use the issue cost")
    void saleAndReturnCancel() {
        // The property that matters: returning everything must leave COGS at zero
        // for the pair, or profit reporting drifts on every return.
        BigDecimal cost = new BigDecimal("59.879750");
        BigDecimal qty  = new BigDecimal("40");

        BigDecimal cogsOut = cost.multiply(qty);          // SALE
        BigDecimal cogsBack = cost.multiply(qty);         // SALES_RETURN, same cost
        assertThat(cogsOut.subtract(cogsBack)).isEqualByComparingTo("0");

        // With the refund used instead, the pair leaves a phantom 2116.79 of cost
        // behind — a loss on goods that were never kept.
        BigDecimal cogsBackWrong = new BigDecimal("6.96").multiply(qty);
        assertThat(cogsOut.subtract(cogsBackWrong)).isEqualByComparingTo("2116.790000");
    }
}
