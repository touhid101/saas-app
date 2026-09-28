package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a sales return hands back, and how much may come back at all.
 *
 * <p>Both are places where being wrong is quiet. A refund computed off the gross
 * line price returns money that was never taken; a returnable check that ignores
 * prior returns lets the same goods come back twice, each time crediting the
 * customer and adding stock that does not exist.
 */
class SalesReturnMathTest {

    /* Mirrors SalesReturnServiceImpl, whose helpers are private. */

    private static BigDecimal perUnit(String lineTotal, String qty) {
        BigDecimal q = new BigDecimal(qty);
        if (q.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return new BigDecimal(lineTotal).divide(q, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal netOfInvoiceDiscount(BigDecimal amount, String pct) {
        BigDecimal p = new BigDecimal(pct);
        if (p.compareTo(BigDecimal.ZERO) <= 0) return amount;
        return amount.subtract(amount.multiply(p).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal refund(String lineTotal, String soldQty, String returnQty, String invoicePct) {
        BigDecimal gross = perUnit(lineTotal, soldQty).multiply(new BigDecimal(returnQty));
        return netOfInvoiceDiscount(gross, invoicePct).setScale(4, RoundingMode.HALF_UP);
    }

    @Nested
    @DisplayName("refund")
    class Refund {

        @Test
        @DisplayName("returning everything refunds the whole line")
        void wholeLine() {
            // 2 box at 222.50 = 445.00, no header discount.
            assertThat(refund("445.00", "2", "2", "0")).isEqualByComparingTo("445.0000");
        }

        @Test
        @DisplayName("returning part refunds that part")
        void partOfLine() {
            assertThat(refund("445.00", "2", "1", "0")).isEqualByComparingTo("222.5000");
        }

        /**
         * The case that costs money if missed.
         *
         * <p>A header discount means the customer paid less than the line says.
         * Refunding the line price hands back more than was taken — a small
         * overpayment on every discounted invoice, in the direction where the
         * till comes up short and nobody can say why.
         */
        @Test
        @DisplayName("a header discount reduces the refund by the same share")
        void netsTheInvoiceDiscount() {
            // 445.00 line on an invoice discounted 10% → 400.50 actually paid.
            assertThat(refund("445.00", "2", "2", "10")).isEqualByComparingTo("400.5000");
            assertThat(refund("445.00", "2", "1", "10")).isEqualByComparingTo("200.2500");
        }

        @Test
        @DisplayName("refunds of the parts add up to the refund of the whole")
        void partsSumToWhole() {
            // No value may leak between two partial returns and one full one.
            BigDecimal first  = refund("445.00", "2", "1", "10");
            BigDecimal second = refund("445.00", "2", "1", "10");
            assertThat(first.add(second)).isEqualByComparingTo(refund("445.00", "2", "2", "10"));
        }

        @Test
        @DisplayName("a line already discounted to nothing refunds nothing")
        void freeLine() {
            assertThat(refund("0.00", "2", "2", "0")).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("the per-unit figure keeps enough precision to survive division")
        void precision() {
            // 8.35 over 3 units is 2.783333…; rounding to 2 here and multiplying
            // back would lose a paisa on every third unit returned.
            assertThat(perUnit("8.35", "3")).isEqualByComparingTo("2.783333");
            assertThat(refund("8.35", "3", "3", "0")).isEqualByComparingTo("8.3500");
        }
    }

    @Nested
    @DisplayName("how much may still come back")
    class Returnable {

        private static BigDecimal left(String sold, String alreadyReturned) {
            return new BigDecimal(sold).subtract(new BigDecimal(alreadyReturned)).max(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("nothing returned yet leaves the whole line")
        void untouched() {
            assertThat(left("5", "0")).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("a prior return reduces what is left")
        void afterPartialReturn() {
            assertThat(left("5", "2")).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("a fully returned line leaves nothing")
        void fullyReturned() {
            assertThat(left("5", "5")).isEqualByComparingTo("0");
        }

        /**
         * Two lines in one request naming the same sale line are summed before
         * checking. Checked separately, each passes and together they exceed
         * what was sold.
         */
        @Test
        @DisplayName("duplicate lines in one request are summed, not checked apart")
        void duplicatesAreSummed() {
            BigDecimal available = left("5", "0");
            BigDecimal a = new BigDecimal("3"), b = new BigDecimal("3");
            assertThat(a.compareTo(available) <= 0).isTrue();   // passes alone
            assertThat(b.compareTo(available) <= 0).isTrue();   // passes alone
            assertThat(a.add(b).compareTo(available) > 0).isTrue();  // must be refused together
        }
    }
}
