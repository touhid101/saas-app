package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.response.ReportResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * All 31 report endpoints in one service.
 * Every method accepts orgId + branchId (nullable = all branches) + date range.
 */
public interface ReportService {

    // ── Sales Reports (9) ─────────────────────────────────────────────────

    ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> salesSummary(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.SalesByProductRow> salesByProduct(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow> salesByCustomer(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.SalesByCompanyRow> salesByCompany(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.SalesByDeliveryManRow> salesByDeliveryMan(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    /** Daily sales trend — one row per calendar date */
    List<ReportResponse.DailyPnlRow> dailySalesTrend(
            Long orgId, Long branchId, LocalDate from, LocalDate to);

    /** Monthly sales trend */
    List<ReportResponse.MonthlyPnlRow> monthlySalesTrend(
            Long orgId, Long branchId, int year);

    ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> creditSales(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> returnedSales(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    // ── Purchase Reports (5) ──────────────────────────────────────────────

    ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> purchaseSummary(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow> purchaseBySupplier(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.PurchaseByProductRow> purchaseByProduct(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> pendingPurchases(
            Long orgId, Long branchId, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> purchaseReturns(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    // ── Inventory Reports (6) ─────────────────────────────────────────────

    ReportResponse.ReportPage<ReportResponse.StockStatusRow> currentStock(
            Long orgId, Long branchId, String category, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.StockStatusRow> lowStock(
            Long orgId, Long branchId, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow> expiryAlert(
            Long orgId, Long branchId, int withinDays, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow> expiredStock(
            Long orgId, Long branchId, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.StockMovementRow> stockMovement(
            Long orgId, Long branchId, Long productId, LocalDate from, LocalDate to,
            Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.StockStatusRow> slowMovingStock(
            Long orgId, Long branchId, int noSalesDays, Pageable pageable);

    // ── Profit & Loss Reports (5) ─────────────────────────────────────────

    /** Daily P&L — actual purchase cost vs actual sales price */
    ReportResponse.ReportPage<ReportResponse.DailyPnlRow> dailyPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    /** Product-wise P&L */
    ReportResponse.ReportPage<ReportResponse.ProductPnlRow> productPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    /** Monthly P&L */
    List<ReportResponse.MonthlyPnlRow> monthlyPnl(Long orgId, Long branchId, int year);

    /** Company-wise P&L */
    ReportResponse.ReportPage<ReportResponse.CompanyPnlRow> companyPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    /** Annual P&L summary (12 months) */
    List<ReportResponse.MonthlyPnlRow> annualPnl(Long orgId, Long branchId, int year);

    // ── Collection / Outstanding Reports (4) ─────────────────────────────

    ReportResponse.ReportPage<ReportResponse.CollectionReportRow> collectionReport(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.CollectionReportRow> collectionByDeliveryMan(
            Long orgId, Long deliveryManId, LocalDate from, LocalDate to, Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.OutstandingDueRow> outstandingDue(
            Long orgId, Long branchId, Pageable pageable);

    /** Customers with overdue payments (beyond specified days) */
    ReportResponse.ReportPage<ReportResponse.OutstandingDueRow> overdueAccounts(
            Long orgId, Long branchId, int overdueDays, Pageable pageable);

    // ── Customer / Supplier Reports (2) ───────────────────────────────────

    ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow> topCustomers(
            Long orgId, Long branchId, LocalDate from, LocalDate to, int limit,
            Pageable pageable);

    ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow> topSuppliers(
            Long orgId, Long branchId, LocalDate from, LocalDate to, int limit,
            Pageable pageable);
}
