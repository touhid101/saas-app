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
 * A purchase return — goods going back to the supplier, against the invoice
 * they arrived on.
 *
 * <p>Like a sales return, the link to the original is the mechanism rather than
 * the paperwork: the purchase line records the batch the goods landed in, its
 * expiry, and what was paid for them. A debit note that guessed at those would
 * take stock out of the wrong lot and credit the wrong amount.
 *
 * <p>Unlike a sales return, this <em>removes</em> stock. A sales return can
 * always be accepted — the goods are physically at the counter. Sending goods
 * back requires they still be on the shelf, so the returnable quantity is
 * bounded twice: by what was bought and not yet returned, and by what remains in
 * that batch. Stock already sold cannot be sent back to the supplier.
 */
@Data
public class PurchaseReturnRequest {

    @NotNull(message = "The purchase invoice being returned against is required")
    private Long originalPurchaseId;

    /** Defaults to today when omitted. */
    private LocalDate transactionDate;

    /**
     * Why the goods are going back — damaged, short-dated, over-supplied.
     *
     * <p>Free text: the useful reasons differ per supplier relationship, and a
     * wrong-but-required category is worse than a note somebody actually reads.
     */
    private String reason;

    /** How the credit is settled — against the account, a refund, a replacement. */
    private String paymentType;

    private String remarks;

    @NotEmpty(message = "A return needs at least one line")
    @Valid
    private List<Line> lines;

    @Data
    public static class Line {
        /** The purchase line being reversed — {@code POS_TRANSACTION_DTL.PID_TRAN_DTL}. */
        @NotNull(message = "Each return line must name the purchase line it reverses")
        private Long originalLineId;

        /**
         * How much is going back, in the unit it was bought in.
         *
         * <p>The purchase unit rather than pieces: a pharmacy sends back two of
         * the boxes it received, and making the storekeeper convert to pieces
         * invites the arithmetic error the unit exists to prevent.
         */
        @NotNull(message = "Return quantity is required")
        @Positive(message = "Return quantity must be greater than zero")
        private BigDecimal quantity;
    }
}
