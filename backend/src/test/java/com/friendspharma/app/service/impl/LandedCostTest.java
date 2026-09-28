package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Landed cost must be net of the invoice-level discount.
 *
 * <p>A line total carries the trade price, IGTA and the <em>line</em> discount.
 * A header discount is applied to the invoice as a whole, so each line bears its
 * proportional share. Omitting it values stock above what was paid and
 * understates every margin measured against it by exactly that percentage —
 * which is what {@code V11} had to go back and restate.
 */
class LandedCostTest {

    private static PosTransactionMst invoice(String discountPct) {
        PosTransactionMst m = new PosTransactionMst();
        if (discountPct != null) m.setInvoiceDisPct(new BigDecimal(discountPct));
        return m;
    }

    private static PosTransactionDtl line(String total) {
        PosTransactionDtl d = new PosTransactionDtl();
        d.setTotalPrice(new BigDecimal(total));
        return d;
    }

    @Test
    @DisplayName("no invoice discount leaves the line total alone")
    void noDiscount() {
        assertThat(StockPostingServiceImpl.netLineTotal(invoice(null), line("58.24")))
                .isEqualByComparingTo("58.24");
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("0"), line("58.24")))
                .isEqualByComparingTo("58.24");
    }

    @Test
    @DisplayName("the line bears its share of a header discount")
    void apportionsHeaderDiscount() {
        // Invoice PU260800026, 2%: 58.24 − 1.16 = 57.08 and 665.33 − 13.31 = 652.02
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("2"), line("58.24")))
                .isEqualByComparingTo("57.08");
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("2"), line("665.33")))
                .isEqualByComparingTo("652.02");
    }

    @Test
    @DisplayName("the apportioned shares add back up to the invoice discount")
    void sharesReconcileToTheInvoice() {
        // The property that matters: no value leaks in the rounding. If the
        // per-line shares did not sum to the header figure, stock valuation and
        // the amount actually paid would disagree by the remainder.
        PosTransactionMst inv = invoice("2");
        List<String> totals = List.of("58.24", "665.33");

        BigDecimal grossSum = BigDecimal.ZERO;
        BigDecimal netSum = BigDecimal.ZERO;
        for (String t : totals) {
            grossSum = grossSum.add(new BigDecimal(t));
            netSum = netSum.add(StockPostingServiceImpl.netLineTotal(inv, line(t)));
        }

        assertThat(grossSum).isEqualByComparingTo("723.57");   // invoice total
        assertThat(netSum).isEqualByComparingTo("709.10");     // amount to be paid
        assertThat(grossSum.subtract(netSum)).isEqualByComparingTo("14.47"); // stated discount
    }

    @Test
    @DisplayName("a null line total is zero, not an exception")
    void nullTotal() {
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("2"), new PosTransactionDtl()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a null invoice is treated as undiscounted")
    void nullInvoice() {
        assertThat(StockPostingServiceImpl.netLineTotal(null, line("100")))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("a full 100% discount makes the line free, not negative")
    void fullDiscount() {
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("100"), line("58.24")))
                .isEqualByComparingTo("0");
    }

    /**
     * The same apportionment governs the selling side.
     *
     * <p>Cost and revenue must be netted the same way or the margin between
     * them is measured against two different bases. The profit report does this
     * in SQL rather than through this helper — these cases pin the arithmetic
     * that both implementations have to agree on.
     */
    @Test
    @DisplayName("sales revenue nets the header discount, exactly as cost does")
    void salesRevenueUsesTheSameRule() {
        // The live case: 2 pcs at 2.94 on an invoice discounted 10%.
        // Charged 5.29, not the 5.88 the lines add up to.
        assertThat(StockPostingServiceImpl.netLineTotal(invoice("10"), line("5.88")))
                .isEqualByComparingTo("5.29");
    }

    @Test
    @DisplayName("netting only one side would move the margin on its own")
    void nettingOneSideDistortsMargin() {
        // Sell for 100 what cost 80, with 10% off the invoice.
        BigDecimal revenue = StockPostingServiceImpl.netLineTotal(invoice("10"), line("100"));
        BigDecimal cost    = new BigDecimal("80");

        // Netted revenue: 90 − 80 = 10 on 90, an 11.11% margin.
        assertThat(revenue).isEqualByComparingTo("90");
        assertThat(revenue.subtract(cost)).isEqualByComparingTo("10");

        // Left un-netted it would read 100 − 80 = 20 on 100, a 20% margin —
        // nearly double, on a sale that never happened at that price.
        assertThat(new BigDecimal("100").subtract(cost)).isEqualByComparingTo("20");
    }
}
