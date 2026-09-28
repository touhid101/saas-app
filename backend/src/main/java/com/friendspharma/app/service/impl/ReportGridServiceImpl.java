package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.ReportGridResponse;
import com.friendspharma.app.dto.response.ReportGridResponse.Column;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.SalesPurchaseReportRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Reports screen, as grids.
 *
 * <p>Each report declares its own columns, so one table renders all of them and
 * a new report needs no frontend work. Every report also declares which filters
 * it honours — showing a category filter above a supplier report invites someone
 * to set it and wonder why nothing changed.
 *
 * <p>This replaces the sales, purchase and finance half of the old
 * {@code /api/v1/reports} surface, whose SQL targets a schema this database does
 * not have. Inventory reports are not duplicated here: they already exist under
 * {@code /api/inventory} against the stock ledger, and a second implementation
 * would be a second set of numbers to reconcile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGridServiceImpl {

    private final SalesPurchaseReportRepository repo;
    /** The base reports are gated on the Reports menu right (MOD14), the same way HR and Accounts gate theirs. */
    private final PermissionGuard permissions;
    static final String MOD_REPORTS = "MOD14";

    /**
     * HR reports live in their own service but on this endpoint.
     *
     * <p>Nineteen more report methods here would have made this class
     * unreadable; two report *engines* would have made the screen choose between
     * them. So the catalogue merges and dispatch delegates — one contract, one
     * endpoint, one grid component on the frontend.
     */
    private final HrReportServiceImpl hrReports;
    /** Accounts reports, the same way: ids prefixed acc-, gated on MOD51. */
    private final AccReportServiceImpl accReports;

    /** What a caller may ask for, and what each one is called on screen. */
    public static final Map<String, String> CATALOGUE = new LinkedHashMap<>() {{
        put("sales-register",     "Sales Register");
        put("sales-by-product",   "Sales by Product");
        put("sales-by-customer",  "Sales by Customer");
        put("sales-by-company",   "Sales by Company");
        put("sales-by-category",  "Sales by Category");
        put("customer-due",       "Customer Due");
        put("purchase-register",  "Purchase Register");
        put("supplier-benefit",   "Supplier Benefit");
        put("purchase-by-product","Purchase by Product");
        put("purchase-by-supplier","Purchase by Supplier");
        put("purchase-return",    "Purchase Return Register");
        put("supplier-due",       "Supplier Outstanding");
        put("supplier-ageing",    "Supplier Ageing");
        put("supplier-payments",  "Supplier Payment Register");
        put("supplier-advance",   "Supplier Advance Register");
        put("daily-pnl",          "Daily Profit & Loss");
        put("customer-profit",    "Customer Profitability");
        put("monthly-pnl",        "Monthly Profit & Loss");
        put("collection-summary", "Collection Summary");
        /* HR appends its own, so the screen sees one list. The ids are all
           prefixed hr-, which is how dispatch routes them without a second
           lookup. */
        putAll(HrReportServiceImpl.CATALOGUE);
        putAll(AccReportServiceImpl.CATALOGUE);
    }};

    @Transactional(readOnly = true)
    public ReportGridResponse run(String id, Filters f) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) throw new ValidationException("Your account has no organisation assigned.");

        // Delivery staff have no business in commercial reporting, and their
        // branch scoping does not apply here — refuse rather than silently
        // return the whole branch.
        if (SecurityUtils.isDeliveryStaff()) {
            throw new ValidationException("Reports are not available to delivery staff.");
        }

        ReportGridResponse out = dispatch(id, orgId, f);

        // The screen must not have to guess. Where the report honours dates and
        // the caller gave none, it says which window it chose rather than
        // presenting three months as though it were everything.
        if (out.getSupportedFilters() != null && out.getSupportedFilters().contains("dateRange")
                && !"customer-due".equals(id)) {
            out.setAppliedFrom(f.since());
            out.setAppliedTo(f.until());
            out.setWindowDefaulted(f.defaulted());
        }
        return out;
    }

    private ReportGridResponse dispatch(String id, Long orgId, Filters f) {
        if (HrReportServiceImpl.owns(id)) return hrReports.run(id, orgId, f);
        if (AccReportServiceImpl.owns(id)) return accReports.run(id, orgId, f);
        // The menu hid these from anyone without the right; the API did not refuse them.
        if (!permissions.has(MOD_REPORTS, PermissionGuard.Right.VIEW)) {
            throw new com.friendspharma.app.exception.custom.BusinessException(
                    "You do not have permission to run reports. Ask somebody whose role holds View on Reports.",
                    org.springframework.http.HttpStatus.FORBIDDEN, "REPORT_NOT_PERMITTED");
        }
        return switch (id) {
            case "sales-register"      -> salesRegister(orgId, f);
            case "sales-by-product"    -> salesByProduct(orgId, f);
            case "sales-by-customer"   -> salesByCustomer(orgId, f, false);
            case "customer-due"        -> salesByCustomer(orgId, f, true);
            case "sales-by-company"    -> salesByCompany(orgId, f);
            case "sales-by-category"   -> salesByCategory(orgId, f);
            case "purchase-register"   -> purchaseRegister(orgId, f, "PU", "Purchase Register");
            case "purchase-return"     -> purchaseRegister(orgId, f, "PR", "Purchase Return Register");
            case "purchase-by-product" -> purchaseByProduct(orgId, f);
            case "purchase-by-supplier"-> purchaseBySupplier(orgId, f);
            case "supplier-benefit"    -> supplierBenefit(orgId, f);
            case "supplier-due"        -> supplierOutstanding(orgId, f, false);
            case "supplier-ageing"     -> supplierOutstanding(orgId, f, true);
            case "supplier-payments"   -> supplierPayments(orgId, f);
            case "supplier-advance"    -> supplierAdvances(orgId, f);
            case "daily-pnl"           -> dailyPnl(orgId, f);
            case "customer-profit"     -> customerProfit(orgId, f);
            case "monthly-pnl"         -> monthlyPnl(orgId, f);
            case "collection-summary"  -> collectionSummary(orgId, f);
            default -> throw new ValidationException(
                    "'" + id + "' is not a report. Available: " + String.join(", ", CATALOGUE.keySet()) + ".");
        };
    }

    /**
     * Three months, where the caller named no dates.
     *
     * <p>Long enough to see a trend and short enough to stay quick on a shop
     * with years of history behind it. Anyone wanting more sets the dates.
     */
    public static final int DEFAULT_WINDOW_DAYS = 90;

    /**
     * The most rows a document-level register will return.
     *
     * <p>One row per invoice, and past a few thousand nobody is reading them —
     * they are exporting, and an export belongs behind its own endpoint that
     * streams. Cut it here rather than hand a browser a hundred thousand rows
     * and let it decide for itself which ones to drop.
     */
    private static final int MAX_REGISTER_ROWS = 5_000;

    /** Trims to the cap, and reports whether anything was cut. */
    private static boolean capped(List<Map<String, Object>> rows) {
        if (rows.size() <= MAX_REGISTER_ROWS) return false;
        rows.subList(MAX_REGISTER_ROWS, rows.size()).clear();
        return true;
    }

    /** Everything a report may be narrowed by. Each report honours a subset. */
    public record Filters(Long branchId, Long companyId, Long categoryId,
                          Long customerId, Long supplierId,
                          LocalDate from, LocalDate to, String q,
                          String paymentStatus,
                          /* Added for the HR reports. A separate field rather
                             than reusing customerId, because a report that
                             filters "the customer" by an employee id is one
                             nobody can read six months later. */
                          Long employeeId, String status,
                          /* The accounting dimension. Separate from branchId,
                             which is where the posting happened; a cost centre
                             is who it was for, and one shop can hold several. */
                          Long costCenterId) {

        /** '' rather than null: PostgreSQL types a null inside LOWER() as bytea. */
        String search() { return q == null || q.isBlank() ? "" : q.trim(); }

        /**
         * The window a report actually runs over.
         *
         * <p>An absent date used to mean no bound, so opening the sales register
         * scanned every invoice the shop had ever raised. That is not a report
         * anybody asked for: it gets slower every month the shop trades, and the
         * answer to "how are we doing" is never three years of rows. An open end
         * is still open — a start date alone means from then until today.
         */
        LocalDate since() {
            if (from != null) return from;
            return (to != null ? to : LocalDate.now()).minusDays(DEFAULT_WINDOW_DAYS - 1L);
        }

        LocalDate until() { return to != null ? to : LocalDate.now(); }

        /** True where neither end was given, so the screen can say what it chose. */
        boolean defaulted() { return from == null && to == null; }
        String payStatus() {
            return paymentStatus == null || paymentStatus.isBlank()
                || "All".equalsIgnoreCase(paymentStatus) ? "" : paymentStatus;
        }

        /** A generic status filter — leave state, attendance state. */
        public String statusFilter() {
            return status == null || status.isBlank()
                || "All".equalsIgnoreCase(status) ? "" : status.trim().toUpperCase();
        }

        /**
         * The leave year a balance report runs for.
         *
         * <p>Taken from the end of the window rather than from today: asking for
         * last December should show last year's entitlement, not this year's.
         */
        public int leaveYear() { return until().getYear(); }

        public LocalDate windowFrom() { return since(); }
        public LocalDate windowTo()   { return until(); }
        public String    text()       { return search(); }
    }

    /* ══════════════════════════════════════════════════════════════════════
       SALES
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse salesRegister(Long orgId, Filters f) {
        var raw = repo.salesRegister(orgId, f.branchId(), f.customerId(),
                                     f.since(), f.until(), f.payStatus(), f.search());
        var cols = List.of(
                col("invoiceNo", "Invoice", "text"), col("date", "Date", "date"),
                col("customer", "Customer", "text"), col("branch", "Branch", "text"),
                col("paymentType", "Payment", "text"), col("stage", "Stage", "text"),
                col("paymentStatus", "Status", "text"),
                money("subtotal", "Subtotal"), money("discount", "Discount"),
                money("payable", "Payable"), money("paid", "Paid"),
                money("returned", "Returned"), money("net", "Net Sale"),
                money("due", "Due"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal payable = bd(r[9]), returned = bd(r[12]);
            // Net is what the customer kept. Floored at zero because a return
            // larger than the invoice it names is a data problem, not a negative
            // sale, and reporting it as one puts a minus sign in a revenue column
            // where nobody would think to question it.
            BigDecimal net = payable.subtract(returned).max(BigDecimal.ZERO);
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8],
                         payable, r[10], returned, net, r[11]));
        }
        // Built before the cap so the footer totals and the row count cover every
        // invoice that matched, not merely the ones that fit. A total over the
        // visible page is worse than no total: it reads like an answer.
        var g = grid("sales-register", "Sales Register", cols, rows,
                "Confirmed invoices onward. A draft has reserved stock but is not yet a sale. "
                + "Returned is what came back against the invoice, and Net Sale is what the "
                + "customer kept. A credit note raised without naming an invoice cannot be "
                + "attributed to one and is counted in Sales by Customer instead.",
                List.of("dateRange", "branch", "customer", "paymentStatus", "search"));
        g.setTruncated(capped(rows));
        return g;
    }

    /**
     * Said on every report that nets returns, because a reader comparing this
     * against a gross figure elsewhere needs to know which one they are holding.
     */
    private static final String RETURNS_NETTED =
            "Sales returns are subtracted. Quantities and revenue are net of what came back, "
            + "and Returned shows how much that was — a line selling 100 and taking 40 back is "
            + "a different business from one selling 60.";

    private ReportGridResponse salesByProduct(Long orgId, Filters f) {
        var raw = repo.salesByProduct(orgId, f.branchId(), f.companyId(), f.categoryId(),
                                      f.since(), f.until(), f.search());
        var cols = List.of(
                col("product", "Product", "text"), col("sku", "SKU", "text"),
                col("company", "Company", "text"), col("category", "Category", "text"),
                num("qty", "Net Qty"), num("pcs", "Net Pieces"),
                num("invoices", "Invoices"), money("returned", "Returned"),
                money("revenue", "Net Revenue"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[7], r[8], r[6]));
        return grid("sales-by-product", "Sales by Product", cols, rows, RETURNS_NETTED,
                List.of("dateRange", "branch", "company", "category", "search"));
    }

    private ReportGridResponse salesByCustomer(Long orgId, Filters f, boolean dueOnly) {
        // What is owed is owed regardless of when it was billed. Defaulting this
        // one to a window would answer "who owes us" by hiding the oldest debts,
        // which are the ones actually worth chasing.
        LocalDate since = dueOnly ? f.from() : f.since();
        LocalDate until = dueOnly ? f.to()   : f.until();
        var raw = repo.salesByCustomer(orgId, f.branchId(), since, until, f.search(), dueOnly);
        var cols = List.of(
                col("customer", "Customer", "text"), col("type", "Type", "text"),
                col("mobile", "Mobile", "text"), num("invoices", "Invoices"),
                money("returned", "Returned"), money("billed", "Net Billed"),
                money("paid", "Net Paid"), money("due", "Due"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[7], r[4], r[5], r[6]));
        return grid(dueOnly ? "customer-due" : "sales-by-customer",
                dueOnly ? "Customer Due" : "Sales by Customer", cols, rows,
                dueOnly
                    ? "Customers still owing once their returns are credited. Due is netted "
                    + "across the account rather than per invoice, so a credit note cancels a "
                    + "bill instead of leaving both on the report. A negative Due is a customer "
                    + "we owe."
                    : RETURNS_NETTED,
                List.of("dateRange", "branch", "search"));
    }

    private ReportGridResponse salesByCompany(Long orgId, Filters f) {
        var raw = repo.salesByCompany(orgId, f.branchId(), f.since(), f.until());
        var cols = List.of(
                col("company", "Company", "text"), num("products", "Products"),
                num("qty", "Net Qty"), num("invoices", "Invoices"),
                money("returned", "Returned"), money("revenue", "Net Revenue"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[4], r[5], r[3]));
        return grid("sales-by-company", "Sales by Company", cols, rows, RETURNS_NETTED,
                List.of("dateRange", "branch"));
    }

    private ReportGridResponse salesByCategory(Long orgId, Filters f) {
        var raw = repo.salesByCategory(orgId, f.branchId(), f.since(), f.until());
        var cols = List.of(
                col("category", "Category", "text"), num("products", "Products"),
                num("qty", "Net Qty"), money("returned", "Returned"),
                money("revenue", "Net Revenue"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[4], r[3]));
        return grid("sales-by-category", "Sales by Category", cols, rows, RETURNS_NETTED,
                List.of("dateRange", "branch"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       PURCHASE
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse purchaseRegister(Long orgId, Filters f, String type, String title) {
        var raw = repo.purchaseRegister(orgId, type, f.branchId(), f.supplierId(),
                                        f.since(), f.until(), f.search());
        var cols = List.of(
                col("invoiceNo", "Invoice", "text"), col("date", "Date", "date"),
                col("supplier", "Supplier", "text"), col("branch", "Branch", "text"),
                col("paymentType", "Payment", "text"), col("status", "Status", "text"),
                money("subtotal", "Subtotal"), money("discount", "Discount"),
                money("payable", "Payable"), money("paid", "Paid"),
                money("credited", "Return Credit"), money("due", "Due"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10], r[11]));
        }
        return grid(type.equals("PU") ? "purchase-register" : "purchase-return", title, cols, rows, null,
                List.of("dateRange", "branch", "supplier", "search"));
    }

    private ReportGridResponse purchaseByProduct(Long orgId, Filters f) {
        var raw = repo.purchaseByProduct(orgId, f.branchId(), f.companyId(), f.since(), f.until(), f.search());
        var cols = List.of(
                col("product", "Product", "text"), col("sku", "SKU", "text"),
                col("company", "Company", "text"), num("qty", "Qty"),
                num("invoices", "Invoices"), money("cost", "Cost"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[5], r[4]));
        return grid("purchase-by-product", "Purchase by Product", cols, rows, null,
                List.of("dateRange", "branch", "company", "search"));
    }

    /** Spend with each supplier over a window — approved purchases only. */
    private ReportGridResponse purchaseBySupplier(Long orgId, Filters f) {
        var raw = repo.purchaseBySupplier(orgId, f.branchId(), f.since(), f.until(), f.search());
        var cols = List.of(
                col("supplier", "Supplier", "text"), col("mobile", "Mobile", "text"),
                num("invoices", "Invoices"), money("billed", "Billed"),
                money("paid", "Paid"), money("credited", "Return Credit"),
                money("due", "Outstanding"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6]));
        return grid("purchase-by-supplier", "Purchase by Supplier", cols, rows,
                "Approved purchases only. Paid includes payments recorded after the invoice.",
                List.of("dateRange", "branch", "search"));
    }

    /**
     * [V67-69] What each supplier gave beyond the price — and what it was worth.
     *
     * <p>The question a buyer cannot otherwise answer: two suppliers quote the
     * same rate, one throws in two boxes in twelve and a thousand taka back at
     * the quarter, and nothing in the system said so. Every part of it was
     * recorded on individual invoices and nowhere added up.
     *
     * <p>Bonus and gift units are counts, cashback and gift value are money, and
     * they are kept in separate columns rather than summed into one score.
     * Adding boxes to taka would be inventing a figure, and a bonus is already
     * paid for in the sense that matters — it lowered the cost per piece of
     * goods that were bought, and valuing it again here would count the same
     * benefit twice.
     */
    private ReportGridResponse supplierBenefit(Long orgId, Filters f) {
        var raw = repo.supplierBenefit(orgId, f.branchId(), f.since(), f.until(), f.search());
        var cols = List.of(
                col("supplier", "Supplier", "text"),
                num("invoices", "Invoices"),
                num("bonusUnits", "Bonus Pcs"),
                num("giftUnits", "Gift Pcs"),
                money("giftValue", "Gift Value"),
                money("cashback", "Cashback"),
                money("billed", "Billed"),
                col("benefitPct", "Benefit %", "percent"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal giftValue = bd(r[4]);
            BigDecimal cashback  = bd(r[5]);
            BigDecimal billed    = bd(r[6]);
            /* Cashback plus gift value over what was billed — what the
               relationship returned as a proportion of what it cost. The bonus
               is deliberately outside this ratio: it is already reflected in a
               lower price per piece, and counting it here as well would report a
               benefit the business received once as though it had it twice. */
            BigDecimal benefit = cashback.add(giftValue);
            /* Against the gross invoice, not against Billed. Billed is
               AMOUNT_TOBE_PAID, which the calculator has already reduced by the
               cashback — dividing by it would divide by a figure the cashback
               itself shrank and report 5.26% where the supplier offered 5%. The
               column still shows Billed, so it agrees with Purchase by Supplier;
               only the ratio adds the cashback back to get what was asked for. */
            rows.add(row(cols, r[0], r[1], r[2], r[3], giftValue, cashback, billed,
                         margin(benefit, billed.add(cashback))));
        }
        return grid("supplier-benefit", "Supplier Benefit", cols, rows,
                "Approved purchases only. Bonus and gift quantities are in pieces, not money. "
                + "Benefit % is cashback and gift value against what was billed — a bonus is "
                + "already counted as a lower cost per piece.",
                List.of("dateRange", "branch", "search"));
    }

    /**
     * What suppliers are owed, as at today.
     *
     * <p>No date range, and that is not an omission. A balance is a position
     * now, not activity in a period: filtering it by date would drop older
     * unpaid bills and report a smaller debt than the business actually has.
     * The ageing view answers the period question honestly instead.
     */
    private ReportGridResponse supplierOutstanding(Long orgId, Filters f, boolean ageing) {
        var raw = repo.supplierOutstanding(orgId, f.branchId(), f.search());

        var cols = ageing
                ? List.of(col("supplier", "Supplier", "text"), col("mobile", "Mobile", "text"),
                          money("d0", "0–30 days"), money("d31", "31–60 days"),
                          money("d61", "61–90 days"), money("d90", "Over 90 days"),
                          money("onAccount", "Unapplied"), money("due", "Outstanding"),
                          col("lastPayment", "Last Paid", "date"))
                : List.of(col("supplier", "Supplier", "text"), col("mobile", "Mobile", "text"),
                          num("invoices", "Invoices"), money("billed", "Billed"),
                          money("paid", "Paid"), money("credited", "Return Credit"),
                          money("unappliedCredit", "Unapplied Credit"),
                          money("onAccount", "Unapplied Paid"), money("advance", "Advance Held"),
                          money("due", "Outstanding"), money("net", "Net Position"),
                          col("lastPurchase", "Last Purchase", "date"),
                          col("lastPayment", "Last Paid", "date"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(ageing
                    ? row(cols, r[0], r[1], r[7], r[8], r[9], r[10], r[13], r[6], r[12])
                    : row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[16], r[13], r[14], r[6], r[15], r[11], r[12]));
        }

        return grid(ageing ? "supplier-ageing" : "supplier-due",
                ageing ? "Supplier Ageing" : "Supplier Outstanding", cols, rows,
                ageing
                    ? "Unpaid balances by age of the invoice, as at today. No credit terms are "
                    + "recorded anywhere in the system, so buckets count days since the bill "
                    + "rather than days overdue. Unapplied is money paid on account and not yet "
                    + "matched to a bill, so the buckets less unapplied give outstanding."
                    : "As at today, across approved purchases only — net of payments recorded "
                    + "against the supplier and of purchase returns credited to the account. "
                    + "Advance Held is our money the supplier still has, so Net Position is what "
                    + "the relationship is worth: positive we owe them, negative they owe us.",
                // No dateRange: see above. Branch, because a balance belongs to the
                // pharmacy that incurred it — see V_SUPPLIER_ACCOUNT_BRANCH.
                List.of("branch", "search"));
    }

    /**
     * Advances handed over and recovered, by voucher.
     *
     * <p>The trade practice behind it: on a discounted invoice the gross is
     * paid and the supplier returns the discount later. Until it comes back it
     * is a receivable, and this is the list to chase it from.
     */
    private ReportGridResponse supplierAdvances(Long orgId, Filters f) {
        var raw = repo.supplierAdvanceRegister(orgId, f.branchId(), f.supplierId(),
                                               f.since(), f.until(), f.search());
        var cols = List.of(
                col("date", "Date", "date"), col("voucher", "Voucher", "text"),
                col("supplier", "Supplier", "text"), col("against", "Against Invoice", "text"),
                col("kind", "Type", "text"), col("mode", "Mode", "text"),
                money("advanced", "Advanced"), money("recovered", "Recovered"),
                col("note", "Note", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        }
        return grid("supplier-advance", "Supplier Advance Register", cols, rows,
                "Advanced less Recovered is what suppliers still hold of ours. A recovery with an "
                + "invoice against it was taken as credit on that bill rather than returned in money.",
                List.of("dateRange", "branch", "supplier", "search"));
    }

    /** Money out, by voucher. */
    private ReportGridResponse supplierPayments(Long orgId, Filters f) {
        var raw = repo.supplierPaymentRegister(orgId, f.branchId(), f.supplierId(),
                                               f.since(), f.until(), f.search());
        var cols = List.of(
                col("voucher", "Voucher", "text"), col("date", "Date", "date"),
                col("supplier", "Supplier", "text"), col("against", "Against Invoice", "text"),
                col("mode", "Mode", "text"), col("ref", "Cheque / Ref", "text"),
                col("kind", "Type", "text"), col("state", "State", "text"),
                money("amount", "Amount"),
                col("note", "Note", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            rows.add(row(cols, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
        }
        return grid("supplier-payments", "Supplier Payment Register", cols, rows,
                "A blank invoice means the money was paid on account rather than against one bill. "
                + "Recoveries are negative — an advance coming back is money in — so the Amount "
                + "column totals to net cash out. "
                + "Cancelled vouchers are listed at zero so the register still reconciles to a bank "
                + "statement that shows the entry and its reversal.",
                List.of("dateRange", "branch", "supplier", "search"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       FINANCE
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse dailyPnl(Long orgId, Filters f) {
        var raw = repo.dailyProfit(orgId, f.branchId(), f.since(), f.until());
        var cols = List.of(
                col("date", "Date", "date"), num("invoices", "Invoices"), num("pcs", "Pieces"),
                money("revenue", "Revenue"), money("cogs", "COGS"),
                money("profit", "Gross Profit"), col("margin", "Margin", "percent"));
        return profitRows("daily-pnl", "Daily Profit & Loss", cols, raw, 0);
    }

    /**
     * Which customers make money. Revenue is what they were charged after the
     * invoice discount; cost is the batch cost of what left the shelf for them;
     * returns come off both. Margin is null where there was no revenue, so a
     * customer who only returned goods does not show a made-up percentage.
     */
    private ReportGridResponse customerProfit(Long orgId, Filters f) {
        var raw = repo.profitByCustomer(orgId, f.branchId(), f.since(), f.until(), f.search());
        var cols = List.of(
                col("customer", "Customer", "text"), col("type", "Type", "text"),
                num("invoices", "Invoices"), num("pcs", "Net pieces"),
                money("revenue", "Net revenue"), money("discount", "Invoice discount"),
                money("cogs", "COGS"), money("profit", "Gross profit"), col("margin", "Margin", "percent"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal revenue = bd(r[4]), cost = bd(r[6]), profit = revenue.subtract(cost);
            rows.add(row(cols, r[0], r[1], r[2], r[3], revenue, bd(r[5]), cost, profit, margin(profit, revenue)));
        }
        return grid("customer-profit", "Customer Profitability", cols, rows,
                "Revenue after the invoice discount, cost at the batch cost each sale actually consumed, returns netted from both. "
                + "The same basis as Product-wise Profit and Daily Profit & Loss, so the three reconcile.",
                List.of("dateRange", "branch", "search"));
    }

    private ReportGridResponse monthlyPnl(Long orgId, Filters f) {
        var raw = repo.monthlyProfit(orgId, f.branchId(), f.since(), f.until());
        var cols = List.of(
                col("month", "Month", "text"), num("invoices", "Invoices"),
                money("revenue", "Revenue"), money("cogs", "COGS"),
                money("profit", "Gross Profit"), col("margin", "Margin", "percent"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal revenue = bd(r[2]), cogs = bd(r[3]);
            BigDecimal profit = revenue.subtract(cogs);
            rows.add(row(cols, r[0], r[1], revenue, cogs, profit, margin(profit, revenue)));
        }
        return grid("monthly-pnl", "Monthly Profit & Loss", cols, rows, PROFIT_NOTE,
                List.of("dateRange", "branch"));
    }

    private ReportGridResponse profitRows(String id, String title, List<Column> cols,
                                          List<Object[]> raw, int unused) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal revenue = bd(r[3]), cogs = bd(r[4]);
            BigDecimal profit = revenue.subtract(cogs);
            rows.add(row(cols, r[0], r[1], r[2], revenue, cogs, profit, margin(profit, revenue)));
        }
        return grid(id, title, cols, rows, PROFIT_NOTE, List.of("dateRange", "branch"));
    }

    /**
     * Says where the numbers come from, because "profit" invites assumptions.
     *
     * <p>The screen this replaces reported a Net Profit of gross × 0.65 — a
     * flat 35% deduction for operating expenses that nobody had entered. It was
     * an invented figure presented as a financial result, so it is not carried
     * over. Gross profit is measurable from the data; net profit is not, until
     * expenses are recorded somewhere.
     */
    private static final String PROFIT_NOTE =
            "Revenue as invoiced against the cost of the batches actually consumed, paired through "
            + "the stock movement ledger. Sales returns net off both sides. Margin is against "
            + "revenue, not cost. Operating expenses are not recorded in the system, so no net "
            + "profit is shown.";

    /* ══════════════════════════════════════════════════════════════════════
       COLLECTION
       ══════════════════════════════════════════════════════════════════════ */

    private ReportGridResponse collectionSummary(Long orgId, Filters f) {
        var raw = repo.collectionSummary(orgId, f.branchId(), f.since(), f.until());
        var cols = List.of(
                col("date", "Date", "date"), num("invoices", "Invoices"),
                money("billed", "Billed"), money("collected", "Collected"),
                money("outstanding", "Outstanding"), col("rate", "Collected %", "percent"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            BigDecimal billed = bd(r[2]), collected = bd(r[3]);
            rows.add(row(cols, r[0], r[1], billed, collected, bd(r[4]), margin(collected, billed)));
        }
        return grid("collection-summary", "Collection Summary", cols, rows,
                "Collected is capped at the amount billed per invoice — cash tendered above the "
                + "invoice total went back as change, not revenue.",
                List.of("dateRange", "branch"));
    }

    /* ══════════════════════════════════════════════════════════════════════
       PLUMBING
       ══════════════════════════════════════════════════════════════════════ */

    static Column col(String key, String label, String type) {
        return Column.builder().key(key).label(label).type(type).build();
    }
    static Column num(String key, String label) {
        return Column.builder().key(key).label(label).type("number").total(true).build();
    }
    static Column money(String key, String label) {
        return Column.builder().key(key).label(label).type("money").total(true).build();
    }

    /** Pairs values with columns positionally, so the two cannot drift apart. */
    static Map<String, Object> row(List<Column> cols, Object... values) {
        if (values.length != cols.size()) {
            throw new IllegalStateException(
                    "Report row has " + values.length + " values for " + cols.size() + " columns");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) m.put(cols.get(i).getKey(), values[i]);
        return m;
    }

    static ReportGridResponse grid(String id, String title, List<Column> cols,
                                    List<Map<String, Object>> rows, String note,
                                    List<String> filters) {
        return ReportGridResponse.builder()
                .id(id).title(title).note(note)
                .columns(cols).rows(rows).rowCount(rows.size())
                .totals(totals(cols, rows))
                .supportedFilters(filters)
                .build();
    }

    /**
     * Column totals over the whole report.
     *
     * <p>Only for columns marked totalable — summing a percentage or a margin
     * produces a number that looks like an answer and is not one.
     */
    static Map<String, Object> totals(List<Column> cols, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Column c : cols) {
            if (!Boolean.TRUE.equals(c.getTotal())) continue;
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) sum = sum.add(bd(r.get(c.getKey())));
            out.put(c.getKey(), sum);
        }
        return out;
    }

    /** Null on a zero denominator — no revenue is not nought per cent margin. */
    static BigDecimal margin(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.compareTo(BigDecimal.ZERO) == 0) return null;
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
