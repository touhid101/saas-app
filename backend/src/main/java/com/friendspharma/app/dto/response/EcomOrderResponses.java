package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An order as the customer who placed it sees it. */
public final class EcomOrderResponses {
    private EcomOrderResponses() {}

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
    }

    @Getter @Builder
    public static class Event {
        private final String status;
        private final String by;
        private final String reason;
        private final LocalDateTime at;
    }

    @Getter @Builder
    public static class Order {
        private final String orderNo;
        private final String status;
        private final Long branchId;
        private final String branchName;
        private final String branchPhone;
        /** The pharmacy's invoice number, so a customer on the phone and the counter say the same thing. */
        private final String invoiceNo;
        private final String customerType;

        private final String deliveryLabel;
        private final String deliveryRecipient;
        private final String deliveryPhone;
        private final String deliveryAddress;

        private final String paymentMethod;
        private final BigDecimal subtotal;
        private final BigDecimal savings;
        private final BigDecimal deliveryCharge;
        /* What a promotion took off, so the figures on the screen add up. An
           order that shows a total smaller than its lines without saying why
           reads as a mistake. [Phase 12, Phase 13] */
        private final String couponCode;
        private final BigDecimal couponDiscount;
        private final BigDecimal pointsRedeemed;
        private final BigDecimal pointsValue;
        /** Null until the order arrives: points are paid on delivery, not on order. */
        private final BigDecimal pointsEarned;
        private final BigDecimal grandTotal;
        private final String note;

        private final LocalDateTime placedAt;
        private final LocalDateTime confirmedAt;
        private final LocalDateTime deliveredAt;
        private final LocalDateTime cancelledAt;
        private final String cancelReason;
        /** True while the customer may still call it off themselves. */
        private final boolean cancellable;

        private final int lineCount;
        private final BigDecimal itemCount;
        private final List<Line> lines;
        private final List<Event> history;
    }

    @Getter @Builder
    public static class Page {
        private final List<Order> items;
        private final int page;
        private final int size;
        private final long total;
        private final int totalPages;
    }
}
