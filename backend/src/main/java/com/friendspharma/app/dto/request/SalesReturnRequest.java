package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A sales return, always against a specific invoice.
 *
 * <p>SRS FR-11 requires returns be linked to the original transaction, and the
 * link is not bookkeeping — it is what makes the return correct. The sale line
 * carries the batch the goods left from, the expiry on that batch, and the price
 * they were sold at. A free-standing return would have to guess all three, and
 * guessing the batch is how returned stock ends up valued at the wrong cost and
 * dispensed past its expiry.
 *
 * <p>So the client sends only <em>which line</em> and <em>how much</em>.
 * Everything else is read from the sale.
 */
@Data
public class SalesReturnRequest {

    @NotNull(message = "The invoice being returned against is required")
    private Long originalSaleId;

    /** Defaults to today when omitted. */
    private LocalDate transactionDate;

    /**
     * Why the goods came back.
     *
     * <p>Not validated against a list: the useful reasons differ per pharmacy,
     * and a wrong-but-required category is worse than free text nobody reads.
     */
    private String reason;

    /**
     * How the refund was settled — CASH, BKASH, adjustment against the account.
     * Recorded for the ledger; it does not change what goes back on the shelf.
     */
    private String paymentType;

    private String remarks;

    @NotEmpty(message = "A return needs at least one line")
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        /** The sale line being reversed — {@code POS_TRANSACTION_DTL.PID_TRAN_DTL}. */
        @NotNull(message = "Each return line must name the sale line it reverses")
        private Long originalLineId;

        /**
         * How much is coming back, in the unit the line was sold in.
         *
         * <p>Deliberately the sale's own unit rather than pieces: a customer
         * returns two of the boxes they bought, and making the counter convert
         * to pieces invites the arithmetic error the unit was there to prevent.
         */
        @NotNull(message = "Return quantity is required")
        @Positive(message = "Return quantity must be greater than zero")
        private BigDecimal quantity;
    }
}
