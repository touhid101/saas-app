package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.dto.response.ReportResponse;
import com.friendspharma.app.service.interfaces.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "31 reports: Sales, Purchase, Inventory, P&L, Collection, Outstanding")
public class ReportController {

    private final ReportService reportService;

    // ── Helpers ────────────────────────────────────────────────────────────

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.unsorted());
    }

    // ══════════════════════════════════════════════════════════════════════
    // SALES REPORTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/sales/summary")
    @Operation(summary = "R01 – Sales summary list")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesSummaryRow>> salesSummary(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.salesSummary(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/by-product")
    @Operation(summary = "R02 – Sales by product (qty + revenue + cost + GP)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesByProductRow>> salesByProduct(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.salesByProduct(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/by-customer")
    @Operation(summary = "R03 – Sales by customer (revenue + collected + outstanding)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow>> salesByCustomer(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.salesByCustomer(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/by-company")
    @Operation(summary = "R04 – Sales by pharmaceutical company")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesByCompanyRow>> salesByCompany(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.salesByCompany(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/by-delivery-man")
    @Operation(summary = "R05 – Sales by delivery man (assigned invoices)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesByDeliveryManRow>> salesByDeliveryMan(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.salesByDeliveryMan(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/daily-trend")
    @Operation(summary = "R06 – Daily sales trend (chart data)")
    public ApiResponse<List<ReportResponse.DailyPnlRow>> dailySalesTrend(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.dailySalesTrend(orgId, branchId, from, to));
    }

    @GetMapping("/sales/monthly-trend")
    @Operation(summary = "R07 – Monthly sales trend for a year (chart data)")
    public ApiResponse<List<ReportResponse.MonthlyPnlRow>> monthlySalesTrend(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam int year) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.monthlySalesTrend(orgId, branchId, year));
    }

    @GetMapping("/sales/credit")
    @Operation(summary = "R08 – Credit sales list")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesSummaryRow>> creditSales(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.creditSales(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/sales/returned")
    @Operation(summary = "R09 – Sales returns")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesSummaryRow>> returnedSales(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.returnedSales(orgId, branchId, from, to, page(page, size)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PURCHASE REPORTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/purchase/summary")
    @Operation(summary = "R10 – Purchase summary list")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow>> purchaseSummary(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.purchaseSummary(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/purchase/by-supplier")
    @Operation(summary = "R11 – Purchase by supplier")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow>> purchaseBySupplier(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.purchaseBySupplier(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/purchase/by-product")
    @Operation(summary = "R12 – Purchase by product")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseByProductRow>> purchaseByProduct(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.purchaseByProduct(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/purchase/pending")
    @Operation(summary = "R13 – Pending purchase orders")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow>> pendingPurchases(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.pendingPurchases(orgId, branchId, page(page, size)));
    }

    @GetMapping("/purchase/returns")
    @Operation(summary = "R14 – Purchase returns")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow>> purchaseReturns(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.purchaseReturns(orgId, branchId, from, to, page(page, size)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // INVENTORY REPORTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/inventory/current-stock")
    @Operation(summary = "R15 – Current stock status")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.StockStatusRow>> currentStock(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.currentStock(orgId, branchId, category, page(page, size)));
    }

    @GetMapping("/inventory/low-stock")
    @Operation(summary = "R16 – Low / below-reorder-level stock")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.StockStatusRow>> lowStock(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.lowStock(orgId, branchId, page(page, size)));
    }

    @GetMapping("/inventory/expiry-alert")
    @Operation(summary = "R17 – Products expiring within N days")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow>> expiryAlert(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "90") int withinDays,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.expiryAlert(orgId, branchId, withinDays, page(page, size)));
    }

    @GetMapping("/inventory/expired")
    @Operation(summary = "R18 – Already expired stock")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow>> expiredStock(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.expiredStock(orgId, branchId, page(page, size)));
    }

    @GetMapping("/inventory/movement")
    @Operation(summary = "R19 – Stock movement for a product (running balance)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.StockMovementRow>> stockMovement(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.stockMovement(orgId, branchId, productId, from, to, page(page, size)));
    }

    @GetMapping("/inventory/slow-moving")
    @Operation(summary = "R20 – Slow-moving stock (no sales in last N days)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.StockStatusRow>> slowMoving(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "60") int noSalesDays,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.slowMovingStock(orgId, branchId, noSalesDays, page(page, size)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROFIT & LOSS REPORTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/pnl/daily")
    @Operation(summary = "R21 – Daily P&L (actual purchase cost vs actual sales price)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.DailyPnlRow>> dailyPnl(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.dailyPnl(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/pnl/by-product")
    @Operation(summary = "R22 – Product-wise P&L")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.ProductPnlRow>> productPnl(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.productPnl(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/pnl/monthly")
    @Operation(summary = "R23 – Monthly P&L for a year")
    public ApiResponse<List<ReportResponse.MonthlyPnlRow>> monthlyPnl(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam int year) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.monthlyPnl(orgId, branchId, year));
    }

    @GetMapping("/pnl/by-company")
    @Operation(summary = "R24 – Company-wise P&L")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.CompanyPnlRow>> companyPnl(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.companyPnl(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/pnl/annual")
    @Operation(summary = "R25 – Annual P&L summary (12 months)")
    public ApiResponse<List<ReportResponse.MonthlyPnlRow>> annualPnl(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam int year) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.annualPnl(orgId, branchId, year));
    }

    // ══════════════════════════════════════════════════════════════════════
    // COLLECTION / OUTSTANDING REPORTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/collection/summary")
    @Operation(summary = "R26 – Collection report")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.CollectionReportRow>> collectionReport(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.collectionReport(orgId, branchId, from, to, page(page, size)));
    }

    @GetMapping("/collection/by-delivery-man/{deliveryManId}")
    @Operation(summary = "R27 – Collections by delivery man")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.CollectionReportRow>> collectionByDeliveryMan(
            @PathVariable Long deliveryManId,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.collectionByDeliveryMan(orgId, deliveryManId, from, to, page(page, size)));
    }

    @GetMapping("/collection/outstanding")
    @Operation(summary = "R28 – Outstanding due (credit customers with unpaid balance)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.OutstandingDueRow>> outstanding(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.outstandingDue(orgId, branchId, page(page, size)));
    }

    @GetMapping("/collection/overdue")
    @Operation(summary = "R29 – Overdue accounts (outstanding beyond N days)")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.OutstandingDueRow>> overdue(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "30") int overdueDays,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.overdueAccounts(orgId, branchId, overdueDays, page(page, size)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // CUSTOMER / SUPPLIER TOP LISTS
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/customers/top")
    @Operation(summary = "R30 – Top customers by sales amount")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow>> topCustomers(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.topCustomers(orgId, branchId, from, to, limit, page(page, size)));
    }

    @GetMapping("/suppliers/top")
    @Operation(summary = "R31 – Top suppliers by purchase amount")
    public ApiResponse<ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow>> topSuppliers(
            @RequestParam(required = false) Long orgId, @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(reportService.topSuppliers(orgId, branchId, from, to, limit, page(page, size)));
    }
}
