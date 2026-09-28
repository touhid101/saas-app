package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.ReportResponse;
import com.friendspharma.app.service.interfaces.ReportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.*;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    @PersistenceContext
    private EntityManager em;

    // ═══════════════════════════════════════════════════════════════════════
    // SALES REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> salesSummary(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String where = buildSalesWhere(orgId, branchId, from, to, "CONFIRMED");
        String sql = "SELECT t.INVOICE_NO, t.INVOICE_DATE, c.CUSTOMER_NAME, b.BRANCH_NAME, " +
                     "t.SUB_TOTAL, t.DISCOUNT_AMOUNT, t.GRAND_TOTAL, t.PAYMENT_TYPE, t.STAGE " +
                     "FROM POS_TRANSACTION_MST t " +
                     "LEFT JOIN POS_CUSTOMER_INFO c ON t.PID_CUSTOMER = c.PID_CUSTOMER " +
                     "LEFT JOIN ALL_BRANCH b ON t.PID_BRANCH = b.PID_BRANCH " +
                     where + " ORDER BY t.INVOICE_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesSummaryRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.SalesSummaryRow.builder()
                    .invoiceNo(str(r[0])).invoiceDate(toDateTime(r[1]))
                    .customerName(str(r[2])).branchName(str(r[3]))
                    .subTotal(bd(r[4])).discountAmount(bd(r[5])).grandTotal(bd(r[6]))
                    .paymentType(str(r[7])).stage(str(r[8])).build());
        }
        long total = countNative("SELECT COUNT(*) FROM POS_TRANSACTION_MST t " + where);
        BigDecimal grandSum = sumNative(
                "SELECT SUM(t.GRAND_TOTAL) FROM POS_TRANSACTION_MST t " + where);
        return page(result, total, pageable,
                Map.of("totalSales", grandSum != null ? grandSum : BigDecimal.ZERO));
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesByProductRow> salesByProduct(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String dateWhere = salesDateWhere(from, to);
        String branchPart = branchId != null ? " AND t.PID_BRANCH = " + branchId : "";
        String sql =
            "SELECT d.PID_PRODUCT, p.PRODUCT_NAME, comp.COMPANY_NAME, " +
            "SUM(d.QTY) AS tot_qty, SUM(d.LINE_TOTAL) AS tot_sales, " +
            "SUM(d.QTY * d.PURCHASE_COST) AS tot_cost " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST = t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT = p.PID_PRODUCT " +
            "LEFT JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY = comp.PID_COMPANY " +
            "WHERE t.PID_ORG = " + orgId + " AND t.STAGE = 'CONFIRMED' " + branchPart + dateWhere +
            " GROUP BY d.PID_PRODUCT, p.PRODUCT_NAME, comp.COMPANY_NAME " +
            " ORDER BY tot_sales DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesByProductRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sales = bd(r[4]);
            BigDecimal cost  = bd(r[5]);
            BigDecimal gp    = sales.subtract(cost);
            BigDecimal gpPct = sales.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(sales, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.SalesByProductRow.builder()
                    .pidProduct(toLong(r[0])).productName(str(r[1])).companyName(str(r[2]))
                    .totalQty(bd(r[3])).totalSalesAmount(sales).totalCost(cost)
                    .grossProfit(gp).profitPct(gpPct).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow> salesByCustomer(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String dateWhere = salesDateWhere(from, to);
        String branchPart = branchId != null ? " AND t.PID_BRANCH = " + branchId : "";
        String sql =
            "SELECT t.PID_CUSTOMER, c.CUSTOMER_NAME, c.CUSTOMER_CODE, " +
            "COUNT(t.PID_TRANSACTION), SUM(t.GRAND_TOTAL), " +
            "SUM(NVL(col.COLLECTED,0)), SUM(t.GRAND_TOTAL) - SUM(NVL(col.COLLECTED,0)) " +
            "FROM POS_TRANSACTION_MST t " +
            "JOIN POS_CUSTOMER_INFO c ON t.PID_CUSTOMER = c.PID_CUSTOMER " +
            "LEFT JOIN (SELECT PID_TRANSACTION, SUM(COLLECTED_AMOUNT) COLLECTED " +
            "           FROM POS_DELIVERY_COLLECTION WHERE STATUS='COLLECTED' " +
            "           GROUP BY PID_TRANSACTION) col ON col.PID_TRANSACTION = t.PID_TRANSACTION " +
            "WHERE t.PID_ORG = " + orgId + " AND t.STAGE='CONFIRMED'" + branchPart + dateWhere +
            " GROUP BY t.PID_CUSTOMER, c.CUSTOMER_NAME, c.CUSTOMER_CODE " +
            " ORDER BY SUM(t.GRAND_TOTAL) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesByCustomerRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.SalesByCustomerRow.builder()
                    .pidCustomer(toLong(r[0])).customerName(str(r[1])).customerCode(str(r[2]))
                    .invoiceCount(toLong(r[3])).totalSalesAmount(bd(r[4]))
                    .totalCollected(bd(r[5])).outstanding(bd(r[6])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesByCompanyRow> salesByCompany(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String dateWhere = salesDateWhere(from, to);
        String branchPart = branchId != null ? " AND t.PID_BRANCH = " + branchId : "";
        String sql =
            "SELECT comp.PID_COMPANY, comp.COMPANY_NAME, " +
            "SUM(d.LINE_TOTAL), SUM(d.QTY * d.PURCHASE_COST) " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST = t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT = p.PID_PRODUCT " +
            "JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY = comp.PID_COMPANY " +
            "WHERE t.PID_ORG = " + orgId + " AND t.STAGE='CONFIRMED'" + branchPart + dateWhere +
            " GROUP BY comp.PID_COMPANY, comp.COMPANY_NAME ORDER BY SUM(d.LINE_TOTAL) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesByCompanyRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sales = bd(r[2]);
            BigDecimal cost  = bd(r[3]);
            BigDecimal gp    = sales.subtract(cost);
            BigDecimal gpPct = sales.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(sales, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.SalesByCompanyRow.builder()
                    .pidCompany(toLong(r[0])).companyName(str(r[1]))
                    .totalSalesAmount(sales).totalCost(cost).grossProfit(gp).profitPct(gpPct)
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesByDeliveryManRow> salesByDeliveryMan(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String dateWhere = salesDateWhere(from, to);
        String branchPart = branchId != null ? " AND t.PID_BRANCH = " + branchId : "";
        String sql =
            "SELECT t.PID_DELIVERY_MAN, e.EMP_NAME, COUNT(t.PID_TRANSACTION), " +
            "SUM(t.GRAND_TOTAL), SUM(NVL(col.COLLECTED,0)) " +
            "FROM POS_TRANSACTION_MST t " +
            "LEFT JOIN HR_EMPLOYEE e ON t.PID_DELIVERY_MAN = e.PID_EMP " +
            "LEFT JOIN (SELECT PID_TRANSACTION, SUM(COLLECTED_AMOUNT) COLLECTED " +
            "           FROM POS_DELIVERY_COLLECTION WHERE STATUS='COLLECTED' " +
            "           GROUP BY PID_TRANSACTION) col ON col.PID_TRANSACTION = t.PID_TRANSACTION " +
            "WHERE t.PID_ORG = " + orgId + " AND t.STAGE='CONFIRMED'" + branchPart + dateWhere +
            " AND t.PID_DELIVERY_MAN IS NOT NULL " +
            " GROUP BY t.PID_DELIVERY_MAN, e.EMP_NAME ORDER BY SUM(t.GRAND_TOTAL) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesByDeliveryManRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sales = bd(r[3]);
            BigDecimal col   = bd(r[4]);
            result.add(ReportResponse.SalesByDeliveryManRow.builder()
                    .pidDeliveryMan(toLong(r[0])).deliveryManName(str(r[1]))
                    .invoiceCount(toLong(r[2])).totalSalesAmount(sales)
                    .totalCollected(col).outstanding(sales.subtract(col)).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public List<ReportResponse.DailyPnlRow> dailySalesTrend(
            Long orgId, Long branchId, LocalDate from, LocalDate to) {
        return buildDailyPnl(orgId, branchId, from, to);
    }

    @Override
    public List<ReportResponse.MonthlyPnlRow> monthlySalesTrend(
            Long orgId, Long branchId, int year) {
        return buildMonthlyPnl(orgId, branchId, year);
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> creditSales(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {
        String where = buildSalesWhere(orgId, branchId, from, to, "CONFIRMED")
                + " AND t.PAYMENT_TYPE='CREDIT'";
        return fetchSalesSummary(where, pageable);
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> returnedSales(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {
        String where = buildSalesWhere(orgId, branchId, from, to, "RETURNED");
        return fetchSalesSummary(where, pageable);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PURCHASE REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> purchaseSummary(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND p.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(p.PURCHASE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql = "SELECT p.PURCHASE_NO, p.PURCHASE_DATE, s.SUPPLIER_NAME, " +
                     "p.TOTAL_AMOUNT, p.PAYMENT_STATUS " +
                     "FROM POS_PURCHASE_MST p " +
                     "JOIN POS_SUPPLIER_INFO s ON p.PID_SUPPLIER = s.PID_SUPPLIER " +
                     "WHERE p.PID_ORG = " + orgId + branchPart + dateWhere +
                     " ORDER BY p.PURCHASE_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.PurchaseSummaryRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.PurchaseSummaryRow.builder()
                    .purchaseNo(str(r[0])).purchaseDate(toDateTime(r[1]))
                    .supplierName(str(r[2])).totalAmount(bd(r[3])).paymentStatus(str(r[4]))
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow> purchaseBySupplier(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND p.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(p.PURCHASE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT s.PID_SUPPLIER, s.SUPPLIER_NAME, COUNT(p.PID_PURCHASE), SUM(p.TOTAL_AMOUNT) " +
            "FROM POS_PURCHASE_MST p JOIN POS_SUPPLIER_INFO s ON p.PID_SUPPLIER=s.PID_SUPPLIER " +
            "WHERE p.PID_ORG=" + orgId + branchPart + dateWhere +
            " GROUP BY s.PID_SUPPLIER, s.SUPPLIER_NAME ORDER BY SUM(p.TOTAL_AMOUNT) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.PurchaseBySupplierRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.PurchaseBySupplierRow.builder()
                    .pidSupplier(toLong(r[0])).supplierName(str(r[1]))
                    .orderCount(toLong(r[2])).totalPurchaseAmount(bd(r[3])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseByProductRow> purchaseByProduct(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND m.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(m.PURCHASE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT d.PID_PRODUCT, p.PRODUCT_NAME, SUM(d.QTY), SUM(d.LINE_TOTAL), " +
            "CASE WHEN SUM(d.QTY)>0 THEN SUM(d.LINE_TOTAL)/SUM(d.QTY) ELSE 0 END " +
            "FROM POS_PURCHASE_DTL d " +
            "JOIN POS_PURCHASE_MST m ON d.PID_PURCHASE=m.PID_PURCHASE " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "WHERE m.PID_ORG=" + orgId + branchPart + dateWhere +
            " GROUP BY d.PID_PRODUCT, p.PRODUCT_NAME ORDER BY SUM(d.LINE_TOTAL) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.PurchaseByProductRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.PurchaseByProductRow.builder()
                    .pidProduct(toLong(r[0])).productName(str(r[1]))
                    .totalQtyPurchased(bd(r[2])).totalCost(bd(r[3])).avgCostPerUnit(bd(r[4]))
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> pendingPurchases(
            Long orgId, Long branchId, Pageable pageable) {

        String branchPart = branchId != null ? " AND p.PID_BRANCH=" + branchId : "";
        String sql = "SELECT p.PURCHASE_NO, p.PURCHASE_DATE, s.SUPPLIER_NAME, " +
                     "p.TOTAL_AMOUNT, p.PAYMENT_STATUS " +
                     "FROM POS_PURCHASE_MST p " +
                     "JOIN POS_SUPPLIER_INFO s ON p.PID_SUPPLIER=s.PID_SUPPLIER " +
                     "WHERE p.PID_ORG=" + orgId + " AND p.PAYMENT_STATUS='PENDING'" + branchPart +
                     " ORDER BY p.PURCHASE_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.PurchaseSummaryRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.PurchaseSummaryRow.builder()
                    .purchaseNo(str(r[0])).purchaseDate(toDateTime(r[1]))
                    .supplierName(str(r[2])).totalAmount(bd(r[3])).paymentStatus(str(r[4]))
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseSummaryRow> purchaseReturns(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {
        // Placeholder — purchase returns tracked via negative-qty purchase detail lines
        return page(Collections.emptyList(), 0, pageable, Collections.emptyMap());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INVENTORY REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.StockStatusRow> currentStock(
            Long orgId, Long branchId, String category, Pageable pageable) {

        String catPart    = category != null ? " AND pc.CATEGORY_NAME = '" + category + "'" : "";
        String branchPart = branchId != null ? " AND p.PID_BRANCH = " + branchId : "";
        String sql =
            "SELECT p.PID_PRODUCT, p.PRODUCT_CODE, p.PRODUCT_NAME, comp.COMPANY_NAME, " +
            "pc.CATEGORY_NAME, p.CURRENT_STOCK, p.REORDER_LEVEL, p.MRP_PCS, " +
            "(p.CURRENT_STOCK * p.PURCHASE_PRICE) AS STOCK_VAL " +
            "FROM POS_PRODUCT_INFO p " +
            "LEFT JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY = comp.PID_COMPANY " +
            "LEFT JOIN POS_PRODUCT_CATEGORY pc ON p.PID_CATEGORY = pc.PID_CATEGORY " +
            "WHERE p.PID_ORG = " + orgId + " AND p.IS_ACTIVE = 1" + branchPart + catPart +
            " ORDER BY p.PRODUCT_NAME";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.StockStatusRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal stock = bd(r[5]);
            BigDecimal reorder = bd(r[6]);
            result.add(ReportResponse.StockStatusRow.builder()
                    .pidProduct(toLong(r[0])).productCode(str(r[1])).productName(str(r[2]))
                    .companyName(str(r[3])).categoryName(str(r[4]))
                    .currentStock(stock).reorderLevel(reorder)
                    .belowReorder(stock.compareTo(reorder) < 0)
                    .mrpPcs(bd(r[7])).stockValue(bd(r[8])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.StockStatusRow> lowStock(
            Long orgId, Long branchId, Pageable pageable) {

        String branchPart = branchId != null ? " AND p.PID_BRANCH=" + branchId : "";
        String sql =
            "SELECT p.PID_PRODUCT, p.PRODUCT_CODE, p.PRODUCT_NAME, comp.COMPANY_NAME, " +
            "pc.CATEGORY_NAME, p.CURRENT_STOCK, p.REORDER_LEVEL, p.MRP_PCS, " +
            "(p.CURRENT_STOCK * p.PURCHASE_PRICE) " +
            "FROM POS_PRODUCT_INFO p " +
            "LEFT JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY=comp.PID_COMPANY " +
            "LEFT JOIN POS_PRODUCT_CATEGORY pc ON p.PID_CATEGORY=pc.PID_CATEGORY " +
            "WHERE p.PID_ORG=" + orgId + " AND p.IS_ACTIVE=1 AND p.CURRENT_STOCK<=p.REORDER_LEVEL"
            + branchPart + " ORDER BY p.CURRENT_STOCK ASC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.StockStatusRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.StockStatusRow.builder()
                    .pidProduct(toLong(r[0])).productCode(str(r[1])).productName(str(r[2]))
                    .companyName(str(r[3])).categoryName(str(r[4]))
                    .currentStock(bd(r[5])).reorderLevel(bd(r[6])).belowReorder(true)
                    .mrpPcs(bd(r[7])).stockValue(bd(r[8])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow> expiryAlert(
            Long orgId, Long branchId, int withinDays, Pageable pageable) {

        String sql =
            "SELECT d.PID_PRODUCT, p.PRODUCT_NAME, d.BATCH_NO, d.EXPIRY_DATE, " +
            "(d.EXPIRY_DATE - SYSDATE), SUM(d.QTY) " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='PURCHASE' " +
            "AND d.EXPIRY_DATE BETWEEN SYSDATE AND SYSDATE+" + withinDays +
            " GROUP BY d.PID_PRODUCT, p.PRODUCT_NAME, d.BATCH_NO, d.EXPIRY_DATE " +
            " ORDER BY d.EXPIRY_DATE ASC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.ExpiryAlertRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.ExpiryAlertRow.builder()
                    .pidProduct(toLong(r[0])).productName(str(r[1])).batchNo(str(r[2]))
                    .expiryDate(toDate(r[3])).daysToExpiry(toLong(r[4])).remainingStock(bd(r[5]))
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.ExpiryAlertRow> expiredStock(
            Long orgId, Long branchId, Pageable pageable) {

        String sql =
            "SELECT d.PID_PRODUCT, p.PRODUCT_NAME, d.BATCH_NO, d.EXPIRY_DATE, " +
            "(d.EXPIRY_DATE - SYSDATE), SUM(d.QTY) " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='PURCHASE' " +
            "AND d.EXPIRY_DATE < SYSDATE " +
            "GROUP BY d.PID_PRODUCT, p.PRODUCT_NAME, d.BATCH_NO, d.EXPIRY_DATE " +
            "ORDER BY d.EXPIRY_DATE ASC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.ExpiryAlertRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.ExpiryAlertRow.builder()
                    .pidProduct(toLong(r[0])).productName(str(r[1])).batchNo(str(r[2]))
                    .expiryDate(toDate(r[3])).daysToExpiry(toLong(r[4])).remainingStock(bd(r[5]))
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.StockMovementRow> stockMovement(
            Long orgId, Long branchId, Long productId, LocalDate from, LocalDate to,
            Pageable pageable) {

        String dateWhere = from != null ? " AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT TRUNC(t.INVOICE_DATE), t.INVOICE_NO, t.TRAN_TYPE, " +
            "p.PRODUCT_NAME, d.QTY " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "WHERE t.PID_ORG=" + orgId + " AND d.PID_PRODUCT=" + productId + dateWhere +
            " ORDER BY t.INVOICE_DATE";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.StockMovementRow> result = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (Object[] r : rows) {
            String type = str(r[2]);
            BigDecimal qty = bd(r[4]);
            running = "PURCHASE".equals(type) ? running.add(qty) : running.subtract(qty);
            result.add(ReportResponse.StockMovementRow.builder()
                    .movementDate(toDate(r[0])).invoiceNo(str(r[1])).movementType(type)
                    .productName(str(r[3])).qty(qty).runningBalance(running).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.StockStatusRow> slowMovingStock(
            Long orgId, Long branchId, int noSalesDays, Pageable pageable) {

        String branchPart = branchId != null ? " AND p.PID_BRANCH=" + branchId : "";
        String sql =
            "SELECT p.PID_PRODUCT, p.PRODUCT_CODE, p.PRODUCT_NAME, comp.COMPANY_NAME, " +
            "pc.CATEGORY_NAME, p.CURRENT_STOCK, p.REORDER_LEVEL, p.MRP_PCS, " +
            "(p.CURRENT_STOCK * p.PURCHASE_PRICE) " +
            "FROM POS_PRODUCT_INFO p " +
            "LEFT JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY=comp.PID_COMPANY " +
            "LEFT JOIN POS_PRODUCT_CATEGORY pc ON p.PID_CATEGORY=pc.PID_CATEGORY " +
            "WHERE p.PID_ORG=" + orgId + " AND p.IS_ACTIVE=1 AND p.CURRENT_STOCK>0 " + branchPart +
            " AND p.PID_PRODUCT NOT IN (" +
            "   SELECT DISTINCT d.PID_PRODUCT FROM POS_TRANSACTION_DTL d " +
            "   JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "   WHERE t.TRAN_TYPE='SALE' AND t.INVOICE_DATE >= SYSDATE-" + noSalesDays + ")" +
            " ORDER BY p.CURRENT_STOCK DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.StockStatusRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal stock = bd(r[5]);
            BigDecimal reorder = bd(r[6]);
            result.add(ReportResponse.StockStatusRow.builder()
                    .pidProduct(toLong(r[0])).productCode(str(r[1])).productName(str(r[2]))
                    .companyName(str(r[3])).categoryName(str(r[4]))
                    .currentStock(stock).reorderLevel(reorder)
                    .belowReorder(stock.compareTo(reorder) < 0)
                    .mrpPcs(bd(r[7])).stockValue(bd(r[8])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROFIT & LOSS REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.DailyPnlRow> dailyPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {
        List<ReportResponse.DailyPnlRow> rows = buildDailyPnl(orgId, branchId, from, to);
        // Apply manual pagination over list (result set is bounded by date range)
        int start = (int) pageable.getOffset();
        int end   = Math.min(start + pageable.getPageSize(), rows.size());
        List<ReportResponse.DailyPnlRow> slice = start >= rows.size()
                ? Collections.emptyList() : rows.subList(start, end);

        BigDecimal totalRevenue = rows.stream().map(ReportResponse.DailyPnlRow::getTotalSalesRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCost = rows.stream().map(ReportResponse.DailyPnlRow::getTotalCostOfGoods)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGp = totalRevenue.subtract(totalCost);

        return page(slice, rows.size(), pageable,
                Map.of("totalRevenue", totalRevenue, "totalCost", totalCost,
                       "totalGrossProfit", totalGp));
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.ProductPnlRow> productPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND t.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT d.PID_PRODUCT, p.PRODUCT_NAME, comp.COMPANY_NAME, " +
            "SUM(d.QTY), SUM(d.LINE_TOTAL), SUM(d.QTY * d.PURCHASE_COST) " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "LEFT JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY=comp.PID_COMPANY " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='SALE' AND t.STAGE='CONFIRMED' "
            + branchPart + dateWhere +
            " GROUP BY d.PID_PRODUCT, p.PRODUCT_NAME, comp.COMPANY_NAME " +
            " ORDER BY SUM(d.LINE_TOTAL)-SUM(d.QTY*d.PURCHASE_COST) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.ProductPnlRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sales = bd(r[4]);
            BigDecimal cost  = bd(r[5]);
            BigDecimal gp    = sales.subtract(cost);
            BigDecimal gpPct = sales.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(sales, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.ProductPnlRow.builder()
                    .pidProduct(toLong(r[0])).productName(str(r[1])).companyName(str(r[2]))
                    .totalQtySold(bd(r[3])).totalSalesRevenue(sales).totalPurchaseCost(cost)
                    .grossProfit(gp).grossProfitPct(gpPct).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public List<ReportResponse.MonthlyPnlRow> monthlyPnl(Long orgId, Long branchId, int year) {
        return buildMonthlyPnl(orgId, branchId, year);
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.CompanyPnlRow> companyPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND t.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT comp.PID_COMPANY, comp.COMPANY_NAME, SUM(d.LINE_TOTAL), " +
            "SUM(d.QTY*d.PURCHASE_COST) " +
            "FROM POS_TRANSACTION_DTL d " +
            "JOIN POS_TRANSACTION_MST t ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "JOIN POS_PRODUCT_INFO p ON d.PID_PRODUCT=p.PID_PRODUCT " +
            "JOIN POS_COMPANY_INFO comp ON p.PID_COMPANY=comp.PID_COMPANY " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='SALE' AND t.STAGE='CONFIRMED'"
            + branchPart + dateWhere +
            " GROUP BY comp.PID_COMPANY, comp.COMPANY_NAME ORDER BY SUM(d.LINE_TOTAL) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.CompanyPnlRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal sales = bd(r[2]);
            BigDecimal cost  = bd(r[3]);
            BigDecimal gp    = sales.subtract(cost);
            BigDecimal gpPct = sales.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(sales, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.CompanyPnlRow.builder()
                    .pidCompany(toLong(r[0])).companyName(str(r[1]))
                    .salesRevenue(sales).costOfGoods(cost).grossProfit(gp).grossProfitPct(gpPct)
                    .build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public List<ReportResponse.MonthlyPnlRow> annualPnl(Long orgId, Long branchId, int year) {
        return buildMonthlyPnl(orgId, branchId, year);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COLLECTION / OUTSTANDING REPORTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.CollectionReportRow> collectionReport(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable) {

        String branchPart = branchId != null ? " AND c.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND c.COLLECTION_DATE BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT c.COLLECTION_DATE, c.INVOICE_NO, c.CUSTOMER_NAME, c.DELIVERY_MAN_NAME, " +
            "c.INVOICE_AMOUNT, c.COLLECTED_AMOUNT, c.BALANCE_AMOUNT, c.PAYMENT_MODE " +
            "FROM POS_DELIVERY_COLLECTION c " +
            "WHERE c.PID_ORG=" + orgId + " AND c.STATUS='COLLECTED'" + branchPart + dateWhere +
            " ORDER BY c.COLLECTION_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.CollectionReportRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.CollectionReportRow.builder()
                    .collectionDate(toDate(r[0])).invoiceNo(str(r[1])).customerName(str(r[2]))
                    .deliveryManName(str(r[3])).invoiceAmount(bd(r[4])).collectedAmount(bd(r[5]))
                    .balanceAmount(bd(r[6])).paymentMode(str(r[7])).build());
        }
        BigDecimal totalCollected = sumNative(
                "SELECT SUM(c.COLLECTED_AMOUNT) FROM POS_DELIVERY_COLLECTION c " +
                "WHERE c.PID_ORG=" + orgId + " AND c.STATUS='COLLECTED'" + branchPart + dateWhere);
        return page(result, result.size(), pageable,
                Map.of("totalCollected", totalCollected != null ? totalCollected : BigDecimal.ZERO));
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.CollectionReportRow> collectionByDeliveryMan(
            Long orgId, Long deliveryManId, LocalDate from, LocalDate to, Pageable pageable) {

        String dateWhere = from != null ? " AND c.COLLECTION_DATE BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT c.COLLECTION_DATE, c.INVOICE_NO, c.CUSTOMER_NAME, c.DELIVERY_MAN_NAME, " +
            "c.INVOICE_AMOUNT, c.COLLECTED_AMOUNT, c.BALANCE_AMOUNT, c.PAYMENT_MODE " +
            "FROM POS_DELIVERY_COLLECTION c " +
            "WHERE c.PID_ORG=" + orgId + " AND c.PID_DELIVERY_MAN=" + deliveryManId +
            " AND c.STATUS='COLLECTED'" + dateWhere + " ORDER BY c.COLLECTION_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.CollectionReportRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.CollectionReportRow.builder()
                    .collectionDate(toDate(r[0])).invoiceNo(str(r[1])).customerName(str(r[2]))
                    .deliveryManName(str(r[3])).invoiceAmount(bd(r[4])).collectedAmount(bd(r[5]))
                    .balanceAmount(bd(r[6])).paymentMode(str(r[7])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.OutstandingDueRow> outstandingDue(
            Long orgId, Long branchId, Pageable pageable) {
        return fetchOutstanding(orgId, branchId, null, pageable);
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.OutstandingDueRow> overdueAccounts(
            Long orgId, Long branchId, int overdueDays, Pageable pageable) {
        return fetchOutstanding(orgId, branchId, overdueDays, pageable);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CUSTOMER / SUPPLIER TOP LISTS
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public ReportResponse.ReportPage<ReportResponse.SalesByCustomerRow> topCustomers(
            Long orgId, Long branchId, LocalDate from, LocalDate to, int limit,
            Pageable pageable) {
        return salesByCustomer(orgId, branchId, from, to,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Override
    public ReportResponse.ReportPage<ReportResponse.PurchaseBySupplierRow> topSuppliers(
            Long orgId, Long branchId, LocalDate from, LocalDate to, int limit,
            Pageable pageable) {
        return purchaseBySupplier(orgId, branchId, from, to,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private List<ReportResponse.DailyPnlRow> buildDailyPnl(
            Long orgId, Long branchId, LocalDate from, LocalDate to) {

        String branchPart = branchId != null ? " AND t.PID_BRANCH=" + branchId : "";
        String dateWhere  = from != null ? " AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '"
                + from + "' AND DATE '" + to + "'" : "";
        String sql =
            "SELECT TRUNC(t.INVOICE_DATE) AS DT, SUM(t.GRAND_TOTAL), " +
            "SUM(d.QTY * d.PURCHASE_COST), SUM(t.DISCOUNT_AMOUNT), COUNT(t.PID_TRANSACTION) " +
            "FROM POS_TRANSACTION_MST t " +
            "LEFT JOIN POS_TRANSACTION_DTL d ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='SALE' AND t.STAGE='CONFIRMED'"
            + branchPart + dateWhere +
            " GROUP BY TRUNC(t.INVOICE_DATE) ORDER BY TRUNC(t.INVOICE_DATE)";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        List<ReportResponse.DailyPnlRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal revenue = bd(r[1]);
            BigDecimal cost    = bd(r[2]);
            BigDecimal gp      = revenue.subtract(cost);
            BigDecimal gpPct   = revenue.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(revenue, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.DailyPnlRow.builder()
                    .date(toDate(r[0])).totalSalesRevenue(revenue)
                    .totalCostOfGoods(cost).grossProfit(gp).grossProfitPct(gpPct)
                    .totalDiscount(bd(r[3])).invoiceCount(toLong(r[4])).build());
        }
        return result;
    }

    private List<ReportResponse.MonthlyPnlRow> buildMonthlyPnl(
            Long orgId, Long branchId, int year) {

        String branchPart = branchId != null ? " AND t.PID_BRANCH=" + branchId : "";
        String sql =
            "SELECT EXTRACT(MONTH FROM t.INVOICE_DATE), SUM(t.GRAND_TOTAL), " +
            "SUM(d.QTY*d.PURCHASE_COST), SUM(t.DISCOUNT_AMOUNT), COUNT(t.PID_TRANSACTION) " +
            "FROM POS_TRANSACTION_MST t " +
            "LEFT JOIN POS_TRANSACTION_DTL d ON d.PID_TRAN_MST=t.PID_TRANSACTION " +
            "WHERE t.PID_ORG=" + orgId + " AND t.TRAN_TYPE='SALE' AND t.STAGE='CONFIRMED' " +
            "AND EXTRACT(YEAR FROM t.INVOICE_DATE)=" + year + branchPart +
            " GROUP BY EXTRACT(MONTH FROM t.INVOICE_DATE) " +
            " ORDER BY EXTRACT(MONTH FROM t.INVOICE_DATE)";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        List<ReportResponse.MonthlyPnlRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            int month = ((Number) r[0]).intValue();
            BigDecimal revenue = bd(r[1]);
            BigDecimal cost    = bd(r[2]);
            BigDecimal gp      = revenue.subtract(cost);
            BigDecimal gpPct   = revenue.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : gp.divide(revenue, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            result.add(ReportResponse.MonthlyPnlRow.builder()
                    .year(year).month(month)
                    .monthLabel(Month.of(month).name().substring(0, 3) + " " + year)
                    .salesRevenue(revenue).costOfGoods(cost).grossProfit(gp).grossProfitPct(gpPct)
                    .totalDiscount(bd(r[3])).invoiceCount(toLong(r[4])).build());
        }
        return result;
    }

    private ReportResponse.ReportPage<ReportResponse.SalesSummaryRow> fetchSalesSummary(
            String where, Pageable pageable) {

        String sql = "SELECT t.INVOICE_NO, t.INVOICE_DATE, c.CUSTOMER_NAME, b.BRANCH_NAME, " +
                     "t.SUB_TOTAL, t.DISCOUNT_AMOUNT, t.GRAND_TOTAL, t.PAYMENT_TYPE, t.STAGE " +
                     "FROM POS_TRANSACTION_MST t " +
                     "LEFT JOIN POS_CUSTOMER_INFO c ON t.PID_CUSTOMER=c.PID_CUSTOMER " +
                     "LEFT JOIN ALL_BRANCH b ON t.PID_BRANCH=b.PID_BRANCH " +
                     where + " ORDER BY t.INVOICE_DATE DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.SalesSummaryRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.SalesSummaryRow.builder()
                    .invoiceNo(str(r[0])).invoiceDate(toDateTime(r[1]))
                    .customerName(str(r[2])).branchName(str(r[3]))
                    .subTotal(bd(r[4])).discountAmount(bd(r[5])).grandTotal(bd(r[6]))
                    .paymentType(str(r[7])).stage(str(r[8])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    private ReportResponse.ReportPage<ReportResponse.OutstandingDueRow> fetchOutstanding(
            Long orgId, Long branchId, Integer overdueDays, Pageable pageable) {

        String branchPart = branchId != null ? " AND t.PID_BRANCH=" + branchId : "";
        String overduePart = overdueDays != null
                ? " AND t.INVOICE_DATE < SYSDATE-" + overdueDays : "";
        String sql =
            "SELECT t.PID_CUSTOMER, c.CUSTOMER_NAME, c.CUSTOMER_CODE, " +
            "SUM(t.GRAND_TOTAL), SUM(NVL(col.COLLECTED,0)), " +
            "SUM(t.GRAND_TOTAL) - SUM(NVL(col.COLLECTED,0)), " +
            "SUM(CASE WHEN t.INVOICE_DATE < SYSDATE-30 THEN 1 ELSE 0 END) " +
            "FROM POS_TRANSACTION_MST t " +
            "JOIN POS_CUSTOMER_INFO c ON t.PID_CUSTOMER=c.PID_CUSTOMER " +
            "LEFT JOIN (SELECT PID_TRANSACTION, SUM(COLLECTED_AMOUNT) COLLECTED " +
            "           FROM POS_DELIVERY_COLLECTION WHERE STATUS='COLLECTED' " +
            "           GROUP BY PID_TRANSACTION) col ON col.PID_TRANSACTION=t.PID_TRANSACTION " +
            "WHERE t.PID_ORG=" + orgId + " AND t.STAGE='CONFIRMED' AND t.PAYMENT_TYPE='CREDIT'"
            + branchPart + overduePart +
            " GROUP BY t.PID_CUSTOMER, c.CUSTOMER_NAME, c.CUSTOMER_CODE " +
            " HAVING SUM(t.GRAND_TOTAL)-SUM(NVL(col.COLLECTED,0)) > 0 " +
            " ORDER BY (SUM(t.GRAND_TOTAL)-SUM(NVL(col.COLLECTED,0))) DESC";

        List<Object[]> rows = executeNative(sql, pageable);
        List<ReportResponse.OutstandingDueRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(ReportResponse.OutstandingDueRow.builder()
                    .pidCustomer(toLong(r[0])).customerName(str(r[1])).customerCode(str(r[2]))
                    .totalInvoiced(bd(r[3])).totalCollected(bd(r[4])).outstanding(bd(r[5]))
                    .overdueInvoices(toLong(r[6])).build());
        }
        return page(result, result.size(), pageable, Collections.emptyMap());
    }

    // ── SQL builder helpers ───────────────────────────────────────────────

    private String buildSalesWhere(Long orgId, Long branchId,
            LocalDate from, LocalDate to, String stage) {
        StringBuilder sb = new StringBuilder("WHERE t.PID_ORG = ").append(orgId);
        if (stage != null)   sb.append(" AND t.STAGE='").append(stage).append("'");
        if (branchId != null) sb.append(" AND t.PID_BRANCH=").append(branchId);
        if (from != null)    sb.append(" AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '")
                               .append(from).append("' AND DATE '").append(to).append("'");
        return sb.toString();
    }

    private String salesDateWhere(LocalDate from, LocalDate to) {
        if (from == null) return "";
        return " AND TRUNC(t.INVOICE_DATE) BETWEEN DATE '" + from + "' AND DATE '" + to + "'";
    }

    // ── Query execution helpers ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Object[]> executeNative(String sql, Pageable pageable) {
        Query q = em.createNativeQuery(sql);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return q.getResultList();
    }

    private long countNative(String sql) {
        Object result = em.createNativeQuery(sql).getSingleResult();
        return result == null ? 0 : ((Number) result).longValue();
    }

    private BigDecimal sumNative(String sql) {
        Object result = em.createNativeQuery(sql).getSingleResult();
        return result == null ? BigDecimal.ZERO : new BigDecimal(result.toString());
    }

    // ── Type-safe converters ───────────────────────────────────────────────

    private String str(Object o)   { return o != null ? o.toString() : null; }
    private Long toLong(Object o)  { return o == null ? 0L : ((Number) o).longValue(); }
    private BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
    private LocalDate toDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d)      return d.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }
    private LocalDateTime toDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (o instanceof java.sql.Date d)       return d.toLocalDate().atStartOfDay();
        return LocalDateTime.parse(o.toString());
    }

    private <T> ReportResponse.ReportPage<T> page(List<T> rows, long total,
            Pageable pageable, Map<String, Object> summary) {
        return ReportResponse.ReportPage.<T>builder()
                .rows(rows).totalRows(total)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .summary(summary)
                .build();
    }
}
