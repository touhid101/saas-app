package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SalesDiscountResponse {

    @Data @Builder
    public static class SalesDiscountDto {
        private Long pidDisc;
        private Long pidOrg;
        private Long pidCompany;
        private String companyName;
        private Long pidProduct;
        private String productName;
        private String discountTier;
        private BigDecimal discountPct;
        private Integer isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** Returned when querying the effective discount for a product+tier during sales */
    @Data @Builder
    public static class EffectiveDiscountDto {
        private Long pidDisc;
        private String discountTier;
        private BigDecimal discountPct;
        /** product / company / org — the level at which this rule was resolved */
        private String appliedAt;
    }
}
