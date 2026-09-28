package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CollectionRequest {

    @Data
    public static class RecordCollectionRequest {
        @NotNull private Long pidOrg;
        @NotNull private Long pidBranch;
        @NotNull private Long pidTransaction;
        private Long pidDeliveryMan;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal collectedAmount;

        private LocalDate collectionDate;   // defaults to today if null

        @Pattern(regexp = "CASH|BANK_TRANSFER|CHEQUE|MOBILE_BANKING")
        private String paymentMode;         // defaults to CASH

        private String chequeNo;
        private String bankName;
        private String transactionRef;
        private String remarks;
    }

    @Data
    public static class UpdateCollectionRequest {
        private BigDecimal collectedAmount;
        private String paymentMode;
        private String chequeNo;
        private String bankName;
        private String transactionRef;
        private String remarks;
    }
}
