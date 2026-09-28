package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * A cart as the customer sees it: priced now, checked against the shelf now.
 * Every figure is worked out at read time from the same engine the till uses —
 * nothing here was stored when the line was added.
 */
public final class EcomCartResponses {
    private EcomCartResponses() {}

    @Getter @Builder
    public static class Line {
        private final Long id;
        private final Long productId;
        private final String name;
        private final String generic;
        private final String company;
        private final String imageUrl;
        private final boolean prescription;

        private final String unit;
        private final BigDecimal qty;
        private final Integer pcsPerUnit;
        private final BigDecimal qtyPcs;

        private final BigDecimal mrp;
        private final BigDecimal listPrice;
        private final BigDecimal discountPct;
        /** What one unit costs this customer now. Null when the pharmacy can no longer sell it this way. */
        private final BigDecimal unitPrice;
        private final BigDecimal lineTotal;
        /** Saved against MRP on this line. */
        private final BigDecimal lineSaving;

        /** Whole units the pharmacy can supply right now. */
        private final Integer available;
        /** True when the pharmacy has enough for this line. */
        private final boolean inStock;
        /** What is wrong with this line, in words, or null when it is fine. */
        private final String problem;
    }

    @Getter @Builder
    public static class Cart {
        private final Long id;
        private final Long branchId;
        private final String branchName;
        private final String branchAddress;
        private final Double branchDistanceKm;
        private final Boolean deliversHere;
        private final BigDecimal minOrderAmount;
        private final String openingHours;

        private final String tier;
        private final String note;
        private final String couponCode;

        private final List<Line> lines;
        private final int lineCount;
        private final BigDecimal itemCount;
        private final BigDecimal subtotal;
        private final BigDecimal savings;

        /* What the pharmacy charges to bring it, worked out from its own policy
           and this basket — never from anything the client sends. [Phase 9] */
        private final BigDecimal deliveryCharge;
        /** One line explaining the charge: "Free delivery", "60 delivery · about 3.2 km". */
        private final String deliveryNote;
        /** The subtotal at which this pharmacy delivers free, when it has one. */
        private final BigDecimal deliveryFreeAbove;
        /** How much more would earn free delivery, when they are short of it. */
        private final BigDecimal addForFreeDelivery;
        /* A coupon the customer has put on this cart. The code is theirs; every
           figure beside it is the server's. [Phase 12] */
        private final BigDecimal couponDiscount;
        private final String couponName;
        /** What to show about the code: the saving, or why it is not applying. */
        private final String couponMessage;
        /** False when a code is attached but no longer worth anything here. */
        private final Boolean couponValid;

        /* Loyalty, as it stands for this basket at this pharmacy. All decided
           on the server: this pharmacy may take points, or may not. [Phase 13] */
        private final BigDecimal pointsBalance;
        /** The most that could be spent here, and what that would take off. */
        private final Integer pointsUsable;
        private final BigDecimal pointsValue;
        private final String pointsMessage;
        /** What this order would earn when it arrives. */
        private final Integer pointsToEarn;

        /** Goods, less any coupon, plus delivery: what placing this order would cost. */
        private final BigDecimal total;

        /** True when every line can be supplied at the quantity asked for. */
        private final boolean ready;
        /** Why checkout is not possible yet, in words, or null. */
        private final String blocker;
    }

    @Getter @Builder
    public static class Summary {
        private final List<Cart> carts;
        private final int cartCount;
        private final BigDecimal itemCount;
        private final BigDecimal subtotal;
    }
}
