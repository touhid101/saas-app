package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An online order as the pharmacy filling it sees it. */
public final class EcomBoardResponses {
    private EcomBoardResponses() {}

    @Getter @Builder
    public static class Row {
        private final String orderNo;
        private final String status;
        /** What this pharmacy would do to it next, or null when there is nothing left to do. */
        private final String nextAction;
        private final String nextLabel;

        private final LocalDateTime placedAt;
        private final String customerName;
        private final String customerPhone;
        private final String customerType;
        /* [V112] Where this customer is approved to buy wholesale. Null on a
           retail account, where it would imply a restriction that does not
           apply. Shown beside the type because "wholesaler" and "wholesaler in
           Rangpur" are different facts when an order looks wrong. */
        private final String customerRegion;

        private final int lineCount;
        private final BigDecimal itemCount;
        private final BigDecimal grandTotal;
        private final String paymentMethod;
        /** True while the money is still to come — COD before delivery. */
        private final boolean unpaid;

        /** The ERP side of the same document. */
        private final Long invoiceId;
        private final String invoiceNo;
        private final String invoiceStage;
        private final Long deliveryManId;
        private final String deliveryManName;

        private final String deliveryArea;
        private final boolean prescription;
    }

    @Getter @Builder
    public static class Line {
        private final Long productId;
        private final String name;
        private final String generic;
        private final String company;
        private final String unit;
        private final BigDecimal qty;
        private final Integer pcsPerUnit;
        private final BigDecimal unitPrice;
        private final BigDecimal lineTotal;
        private final boolean prescription;
        /** The batches the invoice actually took, for the person packing it. */
        private final List<Batch> batches;
    }

    @Getter @Builder
    public static class Batch {
        private final String batchNo;
        private final String expiry;
        private final BigDecimal qty;
        private final String unit;
    }

    @Getter @Builder
    public static class Event {
        private final String status;
        private final String by;
        private final String actorKind;
        private final String reason;
        private final LocalDateTime at;
    }

    @Getter @Builder
    public static class Detail {
        private final String orderNo;
        private final String status;
        private final String nextAction;
        private final String nextLabel;
        private final List<String> allowedActions;

        private final LocalDateTime placedAt;
        private final LocalDateTime confirmedAt;
        private final LocalDateTime deliveredAt;
        private final LocalDateTime cancelledAt;
        private final String cancelReason;
        private final String cancelledByKind;

        private final String customerName;
        private final String customerPhone;
        private final String customerType;
        private final String customerRegion;
        /** The pharmacy the customer chose; the branch that fulfils and invoices. */
        private final Long fulfilBranchId;
        private final String fulfilBranchName;
        /* What a promotion took off, so the figures on the board add up the
           same way they do on the customer's own order. */
        private final String couponCode;
        private final java.math.BigDecimal couponAmount;
        private final java.math.BigDecimal pointsRedeemed;
        private final java.math.BigDecimal pointsValue;
        /** The pharmacy's own customer row this order was billed to. */
        private final Long posCustomerId;

        private final String deliveryLabel;
        private final String deliveryRecipient;
        private final String deliveryPhone;
        private final String deliveryAddress;
        private final BigDecimal deliveryLatitude;
        private final BigDecimal deliveryLongitude;
        private final String customerNote;

        private final String paymentMethod;
        private final BigDecimal subtotal;
        private final BigDecimal discountAmount;
        private final BigDecimal deliveryCharge;
        private final BigDecimal grandTotal;

        private final Long invoiceId;
        private final String invoiceNo;
        private final String invoiceStage;
        private final String invoicePaymentStatus;
        private final BigDecimal invoiceDue;
        private final Long deliveryManId;
        private final String deliveryManName;

        private final List<Line> lines;
        private final List<Event> history;
    }
}
