package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What can still be sent back from one invoice.
 *
 * <p>The return screen is built from this rather than from the invoice itself:
 * an invoice line says what was sold, which is not the same as what remains
 * returnable once part of it has already come back. Showing the sold quantity
 * and letting the server reject the excess would put the counter in the position
 * of discovering the real figure by being refused.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReturnableLineResponse {

    /** The invoice as a whole. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Invoice {
        private Long   saleId;
        private String invoiceNo;
        private LocalDate transactionDate;
        private Long   customerId;
        private String customerName;
        private String stage;
        private String paymentType;

        private BigDecimal invoiceDiscPct;
        private BigDecimal amountTobePaid;

        /**
         * False when nothing on this invoice can be returned — either every line
         * is fully returned, or the invoice never reached a stage where goods
         * left the building.
         */
        private boolean returnable;
        /** Why not, when {@code returnable} is false. Null otherwise. */
        private String  blockedReason;

        /** Returns already raised against this invoice. */
        private List<PriorReturn> priorReturns;

        private List<ReturnableLineResponse> lines;
    }

    /** A return already raised against this invoice. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PriorReturn {
        private Long      returnId;
        private String    invoiceNo;
        private LocalDate transactionDate;
        private BigDecimal amount;
        private String    remarks;
    }

    private Long   lineId;
    private Long   productId;
    private String productName;

    /** The batch the goods left from — where they must go back. */
    private String    batchNo;
    private LocalDate expiryDate;

    /** BOX / STRIP / PCS — the unit quantities below are counted in. */
    private String salesUnit;
    private Integer noOfPcs;

    private BigDecimal soldQty;
    private BigDecimal alreadyReturned;
    /** soldQty − alreadyReturned. Zero means this line is finished. */
    private BigDecimal returnableQty;

    /** What one unit was sold for, after the line's own discount. */
    private BigDecimal unitPrice;
    private BigDecimal salesPrice;
    private BigDecimal discountPct;

    /**
     * What returning one unit refunds, with the invoice-level discount already
     * taken off.
     *
     * <p>A customer who paid less because of a header discount gets back less.
     * Refunding the gross line price would hand back money that was never taken,
     * a little on every return.
     */
    private BigDecimal refundPerUnit;

    /** Expired stock cannot go back on a sellable shelf — flagged, not blocked. */
    private Boolean expired;
}
