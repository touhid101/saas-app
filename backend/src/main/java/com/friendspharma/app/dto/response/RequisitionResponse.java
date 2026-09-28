package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class RequisitionResponse {

    @Data @Builder
    public static class RequisitionLineDto {
        private Long pidReqDtl;
        private Long pidProduct;
        private String productName;
        private Long pidCompany;
        private String companyName;
        private BigDecimal aiRecommendedQty;
        private BigDecimal requestedQty;
        private String unit;
        private BigDecimal estimatedUnitCost;
        private String priority;
        private String remarks;
        /** Live branch stock in pieces (the Current Stock figure), read when the requisition is opened. */
        private BigDecimal currentStock;
        /** Pieces sold in the last 30 days, net of returns, read when the requisition is opened. */
        private BigDecimal avgMonthlySales;
        /** The same two as they stood when the line was raised; null for lines raised before this was recorded. */
        private BigDecimal stockAtRaise;
        private BigDecimal salesAtRaise;
        /** Trade price + IGTA per box today — the figure a purchase order line will carry, the basis the split uses. */
        private BigDecimal unitCostNow;
    }

    @Data @Builder
    public static class RequisitionDto {
        private Long pidRequisition;
        private String requisitionNo;
        private Long pidOrg;
        private Long pidBranch;
        private LocalDate reqDate;
        private String stage;
        /** Σ qty × unitCostNow: what the purchase orders will come to at today's prices, the split's total. */
        private BigDecimal estimatedTotal;
        /** Σ qty × estimatedUnitCost: the estimate the requisition was raised on. */
        private BigDecimal estimatedTotalAtRaise;
        private String source;
        private Long pidCreatedBy;
        private String createdByName;
        private Long pidApprovedBy;
        private String approvedByName;
        private LocalDateTime approvedAt;
        private String remarks;
        private String rejectionReason;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<RequisitionLineDto> lines;
    }

    @Data @Builder
    public static class RequisitionSummaryDto {
        private Long pidRequisition;
        private String requisitionNo;
        private LocalDate reqDate;
        private String stage;
        private String source;
        private int lineCount;
        private LocalDateTime createdAt;
    }
}
