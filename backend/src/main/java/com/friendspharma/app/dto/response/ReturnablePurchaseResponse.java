package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What can still go back to the supplier from one purchase invoice.
 *
 * <p>Each line carries two separate limits and the smaller of them. A sales
 * return only has to ask what was sold; a purchase return also has to ask what
 * is still on the shelf, because goods already dispensed cannot be sent back.
 * Showing only the purchased figure would let a storekeeper enter a quantity the
 * save then refuses, so both are sent and the reason is named.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReturnablePurchaseResponse {

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Invoice {
        private Long   purchaseId;
        private String invoiceNo;
        private String challanNo;
        private LocalDate transactionDate;
        private Long   supplierId;
        private String supplierName;
        private String status;
        private String paymentType;

        private BigDecimal invoiceDiscPct;
        private BigDecimal amountTobePaid;

        /** False when nothing on this invoice can go back, with the reason below. */
        private boolean returnable;
        private String  blockedReason;

        private List<PriorReturn> priorReturns;
        private List<ReturnablePurchaseResponse> lines;
    }

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
    private String sku;

    /** The lot the goods landed in, and must be taken back out of. */
    private String    batchNo;
    private LocalDate expiryDate;

    /** BOX / STRIP / PCS — the unit every quantity below is counted in. */
    private String  purchaseUnit;
    private Integer noOfPcs;

    private BigDecimal purchasedQty;
    private BigDecimal alreadyReturned;

    /** purchasedQty − alreadyReturned: the paperwork limit. */
    private BigDecimal notYetReturned;

    /**
     * What the batch still physically holds, expressed in this line's unit.
     *
     * <p>The stock limit. Goods sold since delivery are gone, and no debit note
     * brings them back.
     */
    private BigDecimal inStockQty;
    /** The same figure in shelf terms — "2 box 3 strip" — for the storekeeper. */
    private String inStockDisplay;

    /** The smaller of the two limits. This is what may actually be entered. */
    private BigDecimal returnableQty;

    /**
     * Set when stock, not paperwork, is the binding limit — so a storekeeper
     * seeing a smaller number than they expect is told why rather than left to
     * guess that the screen is wrong.
     */
    private String limitedBy;

    /** What one unit cost, net of the invoice discount — the credit per unit. */
    private BigDecimal costPerUnit;
    private BigDecimal creditPerUnit;

    private Boolean expired;
}
