package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

public class PosRequest {

    @Data
    public static class AddItemRequest {
        @NotNull private Long pidProduct;
        @NotNull @DecimalMin("0.01") private BigDecimal qty;
        @NotBlank @Pattern(regexp = "BOX|STRIP|PCS") private String salesUnit;
        /** Optional manual override; if null, MRP is fetched from product master */
        private BigDecimal unitPriceOverride;
    }

    @Data
    public static class UpdateItemRequest {
        @NotNull @DecimalMin("0.00") private BigDecimal qty;
        private BigDecimal discountPct;
    }

    @Data
    public static class HoldCartRequest {
        private String holdNote;
    }

    @Data
    public static class RecallCartRequest {
        @NotBlank private String cartRef;
    }

    @Data
    public static class FinaliseCartRequest {
        @NotNull private Long pidCart;
        /** Optional – override customer selected at cart creation */
        private Long pidCustomer;
        /** Additional invoice-level discount % */
        private BigDecimal additionalDiscountPct;
        /** CASH | CREDIT */
        @NotBlank @Pattern(regexp = "CASH|CREDIT") private String paymentType;
        private String remarks;
    }

    @Data
    public static class QuickSaleRequest {
        @NotNull private Long pidOrg;
        @NotNull private Long pidBranch;
        private Long pidCustomer;
        @NotNull @Valid private List<AddItemRequest> items;
        private BigDecimal additionalDiscountPct;
        @NotBlank @Pattern(regexp = "CASH|CREDIT") private String paymentType;
        private String remarks;
    }
}
