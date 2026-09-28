package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the storefront's offer strip, flash-sale rail and voucher wallet read.
 *
 * <p>Every figure here is one the pharmacy or the coupon already holds. There
 * is no "was" price that nobody charged, no sold-count this system does not
 * keep, and no deadline unless a person typed one into a coupon.
 */
public final class EcomOfferResponses {

    private EcomOfferResponses() { }

    /** A medicine whose price is genuinely below its MRP right now. */
    @Getter @Builder
    public static class Deal {
        private final EcomCatalogueResponses.Item item;
        /** The unit the card quotes — the caller's own default where they may buy it. */
        private final String unit;
        private final BigDecimal price;
        private final BigDecimal mrp;
        private final BigDecimal saving;
        private final int savingPct;

        private final BigDecimal stockPcs;
        /** The pharmacy's own reorder level. Null when it has not set one. */
        private final BigDecimal reorderPcs;
        /** 0–100 for the stock bar, or null when there is no reorder level to draw against. */
        private final Integer stockBarPct;
        /** True when stock has fallen to the pharmacy's reorder level. */
        private final boolean runningLow;

        /**
         * Pieces actually sold online at this pharmacy in the last seven days,
         * cancellations and returns excluded. Zero is a real answer and is
         * shown as one — the card says nothing rather than inventing a number.
         */
        private final long soldPcs;
        /**
         * How far through the bar sits: sold ÷ (sold + still in stock). Both
         * halves are counted figures, so the proportion is the real split
         * between what went and what is left, not a target someone chose.
         * Null when nothing has sold and there is nothing to divide.
         */
        private final Integer soldBarPct;
    }

    /** A live coupon, and the cart's own verdict on whether this caller can spend it. */
    @Getter @Builder
    public static class Voucher {
        private final String code;
        private final String name;
        private final String description;
        /** "15% off", "Free delivery", "100 off" — built from the coupon's own type and value. */
        private final String headline;

        private final String discountType;
        private final BigDecimal discountValue;
        private final BigDecimal maxDiscountAmount;
        private final BigDecimal minOrderAmount;

        private final LocalDateTime startsAt;
        private final LocalDateTime endsAt;
        /** Redemptions remaining against the coupon's total limit, or null when it has none. */
        private final Long redemptionsLeft;

        private final boolean usable;
        /** Why it is not spendable yet — the cart's own sentence, e.g. "needs an order of 500 or more". */
        private final String note;
    }

    /** The one offer with a deadline, which is the only thing entitled to a countdown. */
    @Getter @Builder
    public static class Campaign {
        private final String code;
        private final String name;
        private final String description;
        private final String headline;
        private final LocalDateTime endsAt;
        /** Seconds remaining when the server answered — the client ticks from this, not from its own clock. */
        private final long secondsLeft;
        private final Long redemptionsLeft;
        private final BigDecimal minOrderAmount;
    }

    /**
     * Something bought a little while ago — the product, the pharmacy and how
     * long ago, and deliberately nothing about who bought it.
     */
    @Getter @Builder
    public static class RecentOrder {
        private final Long productId;
        private final Long branchId;
        private final String branchName;
        private final String name;
        private final String imageUrl;
        private final BigDecimal price;
        private final BigDecimal mrp;
        private final int savingPct;
        /** "2 minutes ago", "8 hours ago" — coarse on purpose. */
        private final String whenText;
        private final long minutesAgo;
        private final boolean inStock;
        /** Pieces on the shelf now, so the card can print the count like every other card. */
        private final BigDecimal stockPcs;
        /** The unit this caller's account buys in. */
        private final String unit;
        /** True only where staff recorded seeing the pharmacy's licence (V114). */
        private final boolean branchVerified;
    }

    @Getter @Builder
    public static class Offers {
        /** True for a wholesale customer who has not chosen a pharmacy: there is no honest shelf yet. */
        private final boolean needsPharmacy;
        private final String tier;
        private final Long branchId;

        private final Campaign campaign;
        /** The deepest discounts, for the flash rail. */
        private final List<Deal> flash;
        /** Everything else that is genuinely reduced. */
        private final List<Deal> deals;
        private final List<Voucher> vouchers;
        /** What people have been buying here lately — products and times, never buyers. */
        private final List<RecentOrder> recent;

        /** One sentence saying what is on — including when the answer is "nothing". */
        private final String note;
    }
}
