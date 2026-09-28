package com.friendspharma.app.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A purchase order, assembled for printing and sending to the supplier.
 *
 * <p>Deliberately shaped like {@link InvoiceDocumentResponse}: same letterhead,
 * same party block, same line table, same totals block. The two documents leave
 * the same pharmacy and should look as though they did — one goes to a customer,
 * one to a supplier.
 *
 * <p>Every figure arrives computed, as it does for the invoice. Nothing printed
 * on a document sent to a supplier should depend on arithmetic done in a
 * browser.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderDocumentResponse {

    /** The letterhead — the branch placing the order. */
    private InvoiceDocumentResponse.Branch branch;

    private Order order;

    /** Who the order is addressed to. */
    private Party supplier;

    private List<Line> lines;
    private Totals totals;

    /** The grand total spelled out, so a figure cannot be altered in transit. */
    private String amountInWords;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Order {
        private Long id;
        private String orderNo;
        private LocalDate date;

        /**
         * The requisition this order came out of.
         *
         * <p>The reason this document exists: a requisition spans several
         * suppliers and each supplier receives only its own part. Printing the
         * requisition number on it is what lets the buyer and the supplier talk
         * about the same shortage afterwards.
         */
        private String requisitionNo;
        private LocalDate requisitionDate;

        /** DRAFT while it is an order placed, not goods received. */
        private String status;
        private String paymentType;
        private String purchaseType;
        private String remarks;
        private String createdBy;

        /** When this copy was printed — not when the order was raised. */
        private LocalDateTime printedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Party {
        private String name;
        private String contactPerson;
        private String mobile;
        private String phone;
        private String email;
        private String address;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Line {
        private int sl;
        private String product;

        /** BOX, STRIP or PCS — what the quantity beside it counts. */
        private String unit;
        private BigDecimal quantity;

        /**
         * Pieces behind one unit, so a supplier reading "12 BOX" can check the
         * piece count without knowing this pharmacy's packaging.
         */
        private Integer piecesPerUnit;
        private BigDecimal quantityPcs;

        /** Trade price per unit — what is being agreed, not the shelf price. */
        private BigDecimal unitCost;
        /** Printed for reference; a supplier's own price list is quoted on MRP. */
        private BigDecimal mrp;
        private BigDecimal discountAmount;
        private BigDecimal totalPrice;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Totals {
        private BigDecimal subtotal;
        private BigDecimal discount;
        private BigDecimal tax;
        private BigDecimal serviceCharge;
        private BigDecimal grandTotal;
        private int lineCount;
        private BigDecimal totalQuantity;
    }
}
