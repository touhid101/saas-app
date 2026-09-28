package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class CollectionResponse {

    @Data @Builder
    public static class CollectionDto {
        private Long pidCollection;
        private Long pidOrg;
        private Long pidBranch;
        private Long pidTransaction;
        private String invoiceNo;
        private Long pidDeliveryMan;
        private String deliveryManName;
        private Long pidCustomer;
        private String customerName;
        private LocalDate collectionDate;
        private BigDecimal invoiceAmount;
        private BigDecimal collectedAmount;
        private BigDecimal balanceAmount;
        private String paymentMode;
        private String chequeNo;
        private String bankName;
        private String transactionRef;
        private String remarks;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data @Builder
    public static class CollectionSummaryDto {
        private Long pidTransaction;
        private String invoiceNo;
        private BigDecimal invoiceAmount;
        private BigDecimal totalCollected;
        private BigDecimal outstanding;
        private boolean fullyCollected;
    }
}
