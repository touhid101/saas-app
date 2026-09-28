package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.NotificationResponse;
import com.friendspharma.app.dto.response.StockResponses.StockDashboard;
import com.friendspharma.app.repository.HrAdvanceRepository;
import com.friendspharma.app.repository.HrExpenseClaimRepository;
import com.friendspharma.app.repository.HrLeaveRequestRepository;
import com.friendspharma.app.repository.HrLoanRepository;
import com.friendspharma.app.repository.PosRequisitionMstRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.repository.SalesPurchaseReportRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The alert bell, built from the ledger.
 *
 * <p><b>What this replaces.</b> The bell was populated from eight fixed entries
 * in {@code mockData.js}: "8 products below alert quantity", "3 products have
 * zero stock", "12 batches expire within 30 days", and an AI recommendation for
 * good measure. The numbers were invented, identical for every user and every
 * branch, and never changed. A pharmacist reading that bell was reading fiction
 * about a shop that does not exist.
 *
 * <p><b>Two gates, doing different work.</b>
 *
 * <ul>
 *   <li>The bell itself is <b>MOD28 Notifications</b>, granted in RBAC. A role
 *       without it has no bell at all, which the controller enforces.</li>
 *   <li>Each alert is gated by the <b>View right on the module it links to</b>.
 *       Granting the bell therefore never reveals a figure the reader could not
 *       already have looked up: a Pharmacist with no Supplier Payment right sees
 *       no supplier alert, whatever the bell says.</li>
 * </ul>
 *
 * <p>That second gate is what makes the bell safe to hand out. Without it,
 * "notifications" would be a back door to every number in the business.
 *
 * <p><b>Zero raises nothing.</b> An alert reading "0 products are out of stock"
 * is furniture, not information, and a bell that always holds eight unread items
 * is a bell nobody reads. Every alert here is omitted unless its count is above
 * zero.
 *
 * <p><b>Branch-scoped throughout.</b> Every count is for the branch the caller
 * is in. A branch is a pharmacy, and an alert about another pharmacy's expiring
 * stock is both useless and a disclosure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final InventoryService inventory;
    private final PosRequisitionMstRepository requisitionRepo;
    private final PosTransactionMstRepository transactionRepo;
    private final SalesPurchaseReportRepository reportRepo;
    private final PermissionGuard permissions;
    private final BranchScopeService branchScope;
    private final HrLeaveRequestRepository  leaveRepo;
    private final HrExpenseClaimRepository  expenseRepo;
    private final HrAdvanceRepository       advanceRepo;
    private final HrLoanRepository          loanRepo;

    /** The module behind each alert; its View right decides whether the alert is built. */
    private static final String MOD_INVENTORY        = "MOD13";
    private static final String MOD_REQUISITION      = "MOD11";
    private static final String MOD_DELIVERY         = "MOD09";
    private static final String MOD_REPORTS          = "MOD14";
    private static final String MOD_SUPPLIER_PAYMENT = "MOD21";

    /** The stages at which an order has not yet reached the customer. */
    private static final List<String> UNDELIVERED =
            List.of("Confirmed", "Delivery Assigned", "On The Way");

    @Transactional(readOnly = true)
    public List<NotificationResponse> forCurrentUser() {
        Long branchId = branchScope.resolve(null);
        Long orgId = SecurityUtils.getCurrentOrgId();
        List<NotificationResponse> out = new ArrayList<>();

        addStockAlerts(out, branchId);
        addRequisitionAlert(out, orgId, branchId);
        addDeliveryAlert(out, orgId, branchId);
        addCustomerDueAlert(out, orgId, branchId);
        addSupplierDueAlert(out, orgId, branchId);
        addSelfServiceAlerts(out, branchId);

        return out;
    }

    /* ── My HR ───────────────────────────────────────────────────────────── */

    /**
     * The signed-in employee's own requests still waiting for a decision.
     *
     * <p>Gated on the login being linked to an employee rather than on any
     * module right — these are the person's own requests, which they may see
     * whatever their role. Counted with four one-row pages rather than by
     * loading the requests: the bell is read on every sign-in.
     */
    private void addSelfServiceAlerts(List<NotificationResponse> out, Long branchId) {
        Long employeeId = SecurityUtils.getCurrentEmployeeId();
        if (employeeId == null || branchId == null) return;
        var one = org.springframework.data.domain.PageRequest.of(0, 1);
        long waiting = 0;
        try {
            waiting += leaveRepo.search(branchId, employeeId, "SUBMITTED", null, null, one).getTotalElements();
            waiting += expenseRepo.search(branchId, employeeId, "SUBMITTED", null, null, one).getTotalElements();
            waiting += advanceRepo.search(branchId, employeeId, "SUBMITTED", one).getTotalElements();
            waiting += loanRepo.search(branchId, employeeId, "SUBMITTED", one).getTotalElements();
        } catch (RuntimeException e) {
            log.warn("Self-service alert skipped: {}", e.getMessage());
            return;
        }
        add(out, "hris-waiting", "HRIS_PENDING", "My Requests",
            waiting, "request of yours is", "requests of yours are", "waiting for a decision",
            "hris", "INFO", null);
    }

    /* ── Inventory ───────────────────────────────────────────────────────── */

    /**
     * Four alerts from one query.
     *
     * <p>{@code getDashboard} already returns low stock, out of stock, near
     * expiry and expired together. Asking for them separately would be four
     * passes over the batch table for numbers that have to agree with the
     * Inventory screen in any case.
     */
    private void addStockAlerts(List<NotificationResponse> out, Long branchId) {
        if (!permissions.has(MOD_INVENTORY, PermissionGuard.Right.VIEW)) return;

        StockDashboard d = inventory.getDashboard(branchId);

        // Expired first: it is money already lost, and the only one of the four
        // that can no longer be acted on to prevent anything.
        add(out, "expired", "EXPIRED", "Expired Stock",
            d.getExpiredCount(), "batch", "batches", "past their expiry date",
            "inventory", "URGENT", null);

        add(out, "out-of-stock", "OUT_OF_STOCK", "Out of Stock",
            d.getOutOfStockCount(), "product", "products", "with no stock left",
            "inventory", "URGENT", null);

        add(out, "low-stock", "LOW_STOCK", "Low Stock",
            d.getLowStockCount(), "product", "products", "at or below their alert level",
            "inventory", "WARN", null);

        add(out, "near-expiry", "EXPIRY", "Near Expiry",
            d.getNearExpiryCount(), "batch", "batches", "expiring within 30 days",
            "inventory", "WARN", null);
    }

    /* ── Requisition ─────────────────────────────────────────────────────── */

    private void addRequisitionAlert(List<NotificationResponse> out, Long orgId, Long branchId) {
        if (!permissions.has(MOD_REQUISITION, PermissionGuard.Right.VIEW)) return;

        long pending = requisitionRepo.countByBranchAndStage(orgId, branchId, "PENDING_APPROVAL");
        add(out, "requisition-pending", "REQUISITION", "Awaiting Approval",
            pending, "requisition", "requisitions", "waiting to be approved",
            "requisition", "WARN", null);
    }

    /* ── Delivery ────────────────────────────────────────────────────────── */

    /**
     * Orders confirmed but not yet delivered.
     *
     * <p>Counted from the same per-stage query the Sales Activity screen reads,
     * rather than a second definition of "pending" that would drift from it.
     */
    private void addDeliveryAlert(List<NotificationResponse> out, Long orgId, Long branchId) {
        if (!permissions.has(MOD_DELIVERY, PermissionGuard.Right.VIEW)) return;

        long pending = 0;
        for (Object[] row : transactionRepo.countSalesByStage(orgId, branchId, null)) {
            if (row.length > 1 && UNDELIVERED.contains((String) row[0])) {
                pending += ((Number) row[1]).longValue();
            }
        }
        add(out, "delivery-pending", "DELIVERY", "Deliveries Outstanding",
            pending, "order", "orders", "confirmed but not yet delivered",
            "delivery", "INFO", null);
    }

    /* ── Money owed to us ────────────────────────────────────────────────── */

    private void addCustomerDueAlert(List<NotificationResponse> out, Long orgId, Long branchId) {
        if (!permissions.has(MOD_REPORTS, PermissionGuard.Right.VIEW)) return;

        Object[] row = firstRow(reportRepo.customerDueTotals(orgId, branchId));
        if (row == null || row.length < 2) return;

        long customers = row[0] == null ? 0 : ((Number) row[0]).longValue();
        BigDecimal due = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());

        add(out, "customer-due", "CUSTOMER_DUE", "Customer Dues",
            customers, "customer", "customers", "with an unpaid balance",
            "reports", "WARN", due);
    }

    /* ── Money we owe ────────────────────────────────────────────────────── */

    /**
     * Suppliers owed, from the branch-grained account view.
     *
     * <p>Gated on Supplier Payment rather than Reports: that is the screen the
     * reader would act on, and the right that means "may see what we owe".
     */
    private void addSupplierDueAlert(List<NotificationResponse> out, Long orgId, Long branchId) {
        if (!permissions.has(MOD_SUPPLIER_PAYMENT, PermissionGuard.Right.VIEW)) return;

        long suppliers = 0;
        BigDecimal owed = BigDecimal.ZERO;
        // Column 6 is OUTSTANDING; the query also returns suppliers holding an
        // advance, whose outstanding is zero or negative and who are not owed.
        for (Object[] r : reportRepo.supplierOutstanding(orgId, branchId, "")) {
            if (r.length < 7 || r[6] == null) continue;
            BigDecimal outstanding = new BigDecimal(r[6].toString());
            if (outstanding.signum() > 0) {
                suppliers++;
                owed = owed.add(outstanding);
            }
        }
        add(out, "supplier-due", "SUPPLIER_DUE", "Supplier Payments Due",
            suppliers, "supplier", "suppliers", "owed money",
            "supplierpayment", "WARN", owed);
    }

    /* ── Plumbing ────────────────────────────────────────────────────────── */

    /**
     * Adds an alert, unless there is nothing to say.
     *
     * <p>Singular and plural are both passed rather than an "s" being appended,
     * because "1 batches expiring within 30 days" is the kind of detail that
     * makes a reader trust nothing else on the screen.
     */
    private void add(List<NotificationResponse> out, String id, String type, String title,
                     long count, String singular, String plural, String predicate,
                     String link, String severity, BigDecimal amount) {
        if (count <= 0) return;
        out.add(NotificationResponse.builder()
                .id(id)
                .type(type)
                .title(title)
                .message(count + " " + (count == 1 ? singular : plural) + " " + predicate)
                .link(link)
                .count(count)
                .amount(amount)
                .severity(severity)
                .build());
    }

    /**
     * The single row a scalar aggregate returns.
     *
     * <p>A native query projecting one row comes back as {@code Object[]} or as
     * a one-element list of them depending on how Hibernate types the
     * projection. Handled here rather than letting a ClassCastException reach
     * the bell.
     */
    private Object[] firstRow(Object result) {
        if (result instanceof Object[] arr) {
            return (arr.length > 0 && arr[0] instanceof Object[] nested) ? nested : arr;
        }
        if (result instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Object[] arr) {
            return arr;
        }
        return null;
    }
}
