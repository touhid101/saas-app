package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ReportResponse {

    // ── Sales Reports ─────────────────────────────────────────────────────

    @Data @Builder
    public static class SalesSummaryRow {
        private String invoiceNo;
        private LocalDateTime invoiceDate;
        private String customerName;
        private String branchName;
        private BigDecimal subTotal;
        private BigDecimal discountAmount;
        private BigDecimal grandTotal;
        private String paymentType;
        private String stage;
    }

    @Data @Builder
    public static class SalesByProductRow {
        private Long pidProduct;
        private String productName;
        private String companyName;
        private BigDecimal totalQty;
        private BigDecimal totalSalesAmount;
        private BigDecimal totalCost;
        private BigDecimal grossProfit;
        private BigDecimal profitPct;
    }

    @Data @Builder
    public static class SalesByCustomerRow {
        private Long pidCustomer;
        private String customerName;
        private String customerCode;
        private long invoiceCount;
        private BigDecimal totalSalesAmount;
        private BigDecimal totalCollected;
        private BigDecimal outstanding;
    }

    @Data @Builder
    public static class SalesByCompanyRow {
        private Long pidCompany;
        private String companyName;
        private BigDecimal totalSalesAmount;
        private BigDecimal totalCost;
        private BigDecimal grossProfit;
        private BigDecimal profitPct;
    }

    @Data @Builder
    public static class SalesByDeliveryManRow {
        private Long pidDeliveryMan;
        private String deliveryManName;
        private long invoiceCount;
        private BigDecimal totalSalesAmount;
        private BigDecimal totalCollected;
        private BigDecimal outstanding;
    }

    // ── Purchase Reports ──────────────────────────────────────────────────

    @Data @Builder
    public static class PurchaseSummaryRow {
        private String purchaseNo;
        private LocalDateTime purchaseDate;
        private String supplierName;
        private BigDecimal totalAmount;
        private String paymentStatus;
    }

    @Data @Builder
    public static class PurchaseBySupplierRow {
        private Long pidSupplier;
        private String supplierName;
        private long orderCount;
        private BigDecimal totalPurchaseAmount;
    }

    @Data @Builder
    public static class PurchaseByProductRow {
        private Long pidProduct;
        private String productName;
        private BigDecimal totalQtyPurchased;
        private BigDecimal totalCost;
        private BigDecimal avgCostPerUnit;
    }

    // ── Inventory Reports ─────────────────────────────────────────────────

    @Data @Builder
    public static class StockStatusRow {
        private Long pidProduct;
        private String productCode;
        private String productName;
        private String companyName;
        private String categoryName;
        private BigDecimal currentStock;
        private BigDecimal reorderLevel;
        private boolean belowReorder;
        private LocalDate nearestExpiry;
        private BigDecimal mrpPcs;
        private BigDecimal stockValue;
    }

    @Data @Builder
    public static class ExpiryAlertRow {
        private Long pidProduct;
        private String productName;
        private String batchNo;
        private LocalDate expiryDate;
        private long daysToExpiry;
        private BigDecimal remainingStock;
    }

    @Data @Builder
    public static class StockMovementRow {
        private LocalDate movementDate;
        private String invoiceNo;
        private String movementType;   // PURCHASE | SALE | RETURN | ADJUSTMENT
        private String productName;
        private BigDecimal qty;
        private BigDecimal runningBalance;
    }

    // ── Profit & Loss ─────────────────────────────────────────────────────

    @Data @Builder
    public static class DailyPnlRow {
        private LocalDate date;
        private BigDecimal totalSalesRevenue;     // sum of grand_total for confirmed sales
        private BigDecimal totalCostOfGoods;      // sum of (purchase_cost × qty_sold)
        private BigDecimal grossProfit;
        private BigDecimal grossProfitPct;
        private BigDecimal totalDiscount;
        private long invoiceCount;
    }

    @Data @Builder
    public static class ProductPnlRow {
        private Long pidProduct;
        private String productName;
        private String companyName;
        private BigDecimal totalQtySold;
        private BigDecimal totalSalesRevenue;
        private BigDecimal totalPurchaseCost;     // actual purchase cost from POS_TRANSACTION_DTL
        private BigDecimal grossProfit;
        private BigDecimal grossProfitPct;
    }

    @Data @Builder
    public static class MonthlyPnlRow {
        private int year;
        private int month;
        private String monthLabel;
        private BigDecimal salesRevenue;
        private BigDecimal costOfGoods;
        private BigDecimal grossProfit;
        private BigDecimal grossProfitPct;
        private BigDecimal totalDiscount;
        private long invoiceCount;
    }

    @Data @Builder
    public static class CompanyPnlRow {
        private Long pidCompany;
        private String companyName;
        private BigDecimal salesRevenue;
        private BigDecimal costOfGoods;
        private BigDecimal grossProfit;
        private BigDecimal grossProfitPct;
    }

    // ── Collection Reports ────────────────────────────────────────────────

    @Data @Builder
    public static class CollectionReportRow {
        private LocalDate collectionDate;
        private String invoiceNo;
        private String customerName;
        private String deliveryManName;
        private BigDecimal invoiceAmount;
        private BigDecimal collectedAmount;
        private BigDecimal balanceAmount;
        private String paymentMode;
    }

    @Data @Builder
    public static class OutstandingDueRow {
        private Long pidCustomer;
        private String customerName;
        private String customerCode;
        private BigDecimal totalInvoiced;
        private BigDecimal totalCollected;
        private BigDecimal outstanding;
        private long overdueInvoices;
    }

    // ── Generic paginated wrapper ─────────────────────────────────────────

    @Data @Builder
    public static class ReportPage<T> {
        private List<T> rows;
        private long totalRows;
        private int page;
        private int size;
        private Map<String, Object> summary;   // totals / aggregates for the filtered data
    }
}
