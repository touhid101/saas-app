package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class TransactionResponse {

    // Header
    private Long id;
    private String transactionType;  // PU / SL / SR / PR
    private String invoiceNo;
    private String challanNo;
    private LocalDate transactionDate;
    private String purchaseType;
    private String paymentType;
    private String stage;            // for SL: Submitted/Confirmed/...
    private String status;
    private String paymentStatus;

    // Party
    private Long supplierId;   private String supplierName;
    private Long customerId;   private String customerName;
    private Long branchId;     private String branchName;

    // Delivery
    private String deliveryAddress;
    private Long deliveryManId;    private String deliveryManName;
    private BigDecimal deliveryCharge;

    // Financials (calc.js exact match)
    private BigDecimal subtotal;          // SUM(line totals before invoice discount)
    private BigDecimal invoiceDiscPct;
    private BigDecimal invoiceDiscAmt;
    private BigDecimal totalDiscount;
    private BigDecimal totalTax;
    private BigDecimal serviceCharge;
    private BigDecimal amountTobePaid;    // totalAmount + tax + serviceCharge - invoiceDiscount
    private BigDecimal givenAmount;
    private BigDecimal due;               // amountTobePaid - givenAmount

    /**
     * The merge this order was folded into, if any.
     *
     * <p>Set makes the order read-only: it is delivered and collected under
     * the merge, and changing it in place would alter what that merge is worth
     * without the merge knowing.
     */
    private Long    mergedIntoId;
    private String  mergedIntoInvoiceNo;
    /** True on the grouping document itself, not the orders inside it. */
    private Boolean mergeInvoice;

    private String remarks;

    /** Where the sale came from: { API} for the till, { ECOM} for a marketplace order. */
    private String source;
    /** The marketplace order number (ECOM-YYMM-NNNNNN), on rows that came from one. */
    private String ecomOrderNo;
    /* Where it is going and who to ring, on a marketplace order — the ERP's own
       DELIVERY_ADDRESS is one line of free text and carries neither. [Phase 9] */
    private BigDecimal deliveryLatitude;
    private BigDecimal deliveryLongitude;
    private String deliveryPhone;
    private String deliveryRecipient;

    // Cancellation — set together, or not at all
    private String cancelledBy;
    private java.time.LocalDateTime cancelledDate;
    private String cancelReason;

    // Lines
    private List<TransactionLineResponse> lines;

    @Getter @Builder
    public static class TransactionLineResponse {
        private Long lineId;
        private Long productId;     private String productName;
        private String batchNo;
        private LocalDate expiryDate;
        private BigDecimal quantity;
        private BigDecimal mnpPrice;    // purchase/cost price
        private BigDecimal mrpPrice;
        private BigDecimal salesPrice;
        private BigDecimal discountPct;
        private BigDecimal flatDiscount;
        private BigDecimal totalPrice;  // lineTotal
        private String salesUnit;       // BOX/STRIP/PCS
        private BigDecimal returnQty;
    }
}
