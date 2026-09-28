package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

public class SalesDiscountRequest {

    @Data
    public static class CreateSalesDiscountRequest {

        @NotNull
        private Long pidOrg;

        /** Optional – null = applies to all companies */
        private Long pidCompany;

        /** Optional – null = applies to all products under the company/org */
        private Long pidProduct;

        /**
         * BOX | STRIP | PCS | SPECIAL
         */
        @NotBlank
        @Pattern(regexp = "BOX|STRIP|PCS|SPECIAL",
                 message = "discountTier must be BOX, STRIP, PCS or SPECIAL")
        private String discountTier;

        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00")
        private BigDecimal discountPct;
    }

    @Data
    public static class UpdateSalesDiscountRequest {

        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00")
        private BigDecimal discountPct;

        private Integer isActive;
    }

    /** Used to look up the effective discount during a sales line */
    @Data
    public static class EffectiveDiscountQuery {

        @NotNull private Long orgId;
        @NotNull private Long productId;
        @NotNull private Long companyId;
        /** BOX | STRIP | PCS | SPECIAL */
        @NotBlank private String tier;
    }
}
