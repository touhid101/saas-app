package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryResponses {

    private InventoryResponses() {}

    @Getter @Builder
    public static class StockLedgerItem {
        private Long productId;
        private String productName;
        private String categoryName;
        private String companyName;
        private BigDecimal currentStock;
        private BigDecimal stockValue;
        private Integer alertQty;
        private String stockStatus; // OK / LOW / OUT
        private String imgColor;
    }

    @Getter @Builder
    public static class BatchStockItem {
        private Long productId;
        private String productName;
        private String batchNo;
        private LocalDate expiryDate;
        private int daysToExpiry;
        private BigDecimal qtyPcs;
        private BigDecimal purchasePrice;
        private BigDecimal mrp;
        private BigDecimal salesPrice;
        private BigDecimal stockValue;
        private String expiryStatus; // OK / NEAR_EXPIRY / EXPIRED
    }

    @Getter @Builder
    public static class FefoAllocationItem {
        private Long productId;
        private String productName;
        private String batchNo;
        private LocalDate expiryDate;
        private BigDecimal available;
        private BigDecimal required;
        private BigDecimal allocated;
    }

    @Getter @Builder
    public static class FefoAllocationResult {
        private Long productId;
        private String productName;
        private BigDecimal totalRequested;
        private BigDecimal totalAllocated;
        private BigDecimal shortfall;
        private List<FefoAllocationItem> batches;
        private boolean fullyAllocatable;
    }

    @Getter @Builder
    public static class InventoryDashboard {
        private BigDecimal totalStockValue;
        private BigDecimal totalUnits;
        private long lowStockCount;
        private long outOfStockCount;
        private long nearExpiryCount;
        private long expiredCount;
    }
}
