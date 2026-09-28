package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Coupons, as the pharmacy that writes them sees them. */
public final class EcomCouponResponses {
    private EcomCouponResponses() {}

    /** What a coupon looks like on the list and in the drawer. */
    @Getter @Builder
    public static class Row {
        private final Long id;
        private final String code;
        private final String name;
        private final String description;

        private final String discountType;
        private final BigDecimal discountValue;
        private final BigDecimal maxDiscountAmount;
        private final BigDecimal minOrderAmount;

        private final String branchScope;
        private final String customerTypeScope;
        private final boolean firstOrderOnly;

        private final Integer usageLimitTotal;
        private final Integer usageLimitPerCustomer;
        private final LocalDateTime startsAt;
        private final LocalDateTime endsAt;

        private final boolean active;
        /** Running · Scheduled · Expired · Taken up · Stopped — one word for the list. */
        private final String state;

        /** How many orders have spent it, and what those discounts came to. */
        private final long timesUsed;
        private final BigDecimal spent;
        /** The whole thing in one sentence. */
        private final String summary;
    }

    @Getter @Builder
    public static class BranchRef {
        private final Long branchId;
        private final String branchName;
    }

    @Getter @Builder
    public static class Detail {
        private final Row coupon;
        private final List<BranchRef> branches;
    }

    /** What the screen sends back. Every figure is re-checked server-side. */
    @Data
    public static class Form {
        private String code;
        private String name;
        private String description;
        private String discountType;
        private BigDecimal discountValue;
        private BigDecimal maxDiscountAmount;
        private BigDecimal minOrderAmount;
        private String branchScope;
        private List<Long> branchIds;
        private String customerTypeScope;
        private String firstOrderOnly;
        private Integer usageLimitTotal;
        private Integer usageLimitPerCustomer;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
        private String isActive;
    }
}
