package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.ReportGridResponse;
import com.friendspharma.app.dto.response.ReportGridResponse.Column;
import com.friendspharma.app.entity.AccAccount;
import com.friendspharma.app.entity.AccBusinessDay;
import com.friendspharma.app.entity.AccProfitAllocation;
import com.friendspharma.app.entity.AccProfitDistribution;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.accounting.AccChartService;
import com.friendspharma.app.service.impl.accounting.AccLedgerService;
import com.friendspharma.app.service.impl.accounting.AccScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.friendspharma.app.service.impl.ReportGridServiceImpl.*;

/**
 * The accounting reports, on the report grid every other report uses.
 *
 * <p>Same contract as {@link HrReportServiceImpl}: ids prefixed {@code acc-},
 * dispatched by prefix, gated on View for Accounts Reports (MOD51). Every
 * figure is read from the ledger through {@link AccLedgerService}; none of
 * these reports opens a sales, purchase or payroll table.
 */
@Service
@RequiredArgsConstructor
public class AccReportServiceImpl {

    private final AccScope scope;
    private final AccLedgerService ledger;
    private final AccChartService chart;
    private final AccBusinessDayRepository dayRepo;
    private final AccJournalRepository journalRepo;
    private final AccEventRepository eventRepo;
    private final AccProfitDistributionRepository distRepo;
    private final AccProfitAllocationRepository allocationRepo;
    private final AccInvestorRepository investorRepo;
    private final AllBranchRepository branchRepo;
    /** The invoice tables, only to tie the AR / AP control accounts to what the invoices say is open. */
    private final SalesPurchaseReportRepository invoices;

    public static final Map<String, String> CATALOGUE = new LinkedHashMap<>() {{
        put("acc-trial-balance",       "Trial Balance");
        put("acc-profit-loss",         "Profit & Loss");
        put("acc-balance-sheet",       "Balance Sheet");
        put("acc-cash-flow",           "Cash Flow");
        put("acc-general-ledger",      "General Ledger");
        put("acc-journal-register",    "Journal Register");
        put("acc-daily-summary",       "Daily Transaction Summary");
        put("acc-branch-pnl",          "Branch-wise Profit & Loss");
        put("acc-revenue-analysis",    "Revenue Analysis");
        put("acc-expense-analysis",    "Expense Analysis");
        put("acc-ar-movement",         "Receivable Movement");
        put("acc-ap-movement",         "Payable Movement");
        put("acc-inventory-movement",  "Inventory Financial Movement");
        put("acc-ar-ageing",           "Receivables Ageing");
        put("acc-ap-ageing",           "Payables Ageing");
        put("acc-customer-statement",  "Customer Statement");
        put("acc-supplier-statement",  "Supplier Statement");
        put("acc-cash-book",           "Cash Book");
        put("acc-bank-book",           "Bank Book");
        put("acc-petty-cash-book",     "Petty Cash Book");
        put("acc-daily-expense",       "Daily Expense Report");
        put("acc-daily-cash",          "Daily Cash Report");
        put("acc-branch-cash-book",    "Branch Cash Book");
        put("acc-cash-closing",        "Cash Closing Report");
        put("acc-cash-difference",     "Cash Short / Over Report");
        put("acc-cash-reconciliation", "Cash Reconciliation");
        put("acc-investor-statement",  "Investor Statement");
        put("acc-investor-capital",    "Investor Capital Report");
        put("acc-investor-profit",     "Investor Profit Statement");
        put("acc-profit-distribution", "Profit Distribution Report");
        put("acc-payroll-summary",     "Payroll Summary (ledger)");
        put("acc-salary-expense",      "Salary Expense");
        put("acc-tax",                 "Tax / VAT Report");
        put("acc-events",              "Accounting Event Register");
    }};

    public static boolean owns(String id) { return id != null && CATALOGUE.containsKey(id); }

    @Transactional(readOnly = true)
    public ReportGridResponse run(String id, Long orgId, ReportGridServiceImpl.Filters f) {
        scope.require(AccScope.MOD_REPORTS, PermissionGuard.Right.VIEW, "run an accounting report");
        Long branch = scope.branchFilter(f.branchId());
        LocalDate from = f.since(), to = f.until();
        return switch (id) {
            case "acc-trial-balance"       -> trialBalance(orgId, branch, from, to);
            case "acc-profit-loss"         -> profitLoss(orgId, branch, from, to, "acc-profit-loss", "Profit & Loss");
            case "acc-balance-sheet"       -> balanceSheet(orgId, branch, to);
            case "acc-cash-flow"           -> cashFlow(orgId, branch, from, to);
            case "acc-general-ledger"      -> generalLedger(orgId, branch, f, from, to);
            case "acc-journal-register"    -> journalRegister(orgId, branch, from, to);
            case "acc-daily-summary"       -> dailySummary(orgId, branch, from, to);
            case "acc-daily-expense"       -> dailyExpense(orgId, branch, from, to, f.costCenterId());
            case "acc-branch-pnl"          -> branchPnl(orgId, branch, from, to);
            case "acc-revenue-analysis"    -> analysis(orgId, branch, from, to, "REVENUE", "acc-revenue-analysis", "Revenue Analysis");
            case "acc-expense-analysis"    -> analysis(orgId, branch, from, to, "EXPENSE", "acc-expense-analysis", "Expense Analysis");
            case "acc-ar-movement"         -> controlMovement(orgId, branch, from, to, "AR", "acc-ar-movement", "Receivable Movement");
            case "acc-ap-movement"         -> controlMovement(orgId, branch, from, to, "AP", "acc-ap-movement", "Payable Movement");
            case "acc-inventory-movement"  -> controlMovement(orgId, branch, from, to, "INVENTORY", "acc-inventory-movement", "Inventory Financial Movement");
            case "acc-ar-ageing"           -> ageing(orgId, branch, to, "CUSTOMER", "AR", "acc-ar-ageing", "Receivables Ageing");
            case "acc-ap-ageing"           -> ageing(orgId, branch, to, "SUPPLIER", "AP", "acc-ap-ageing", "Payables Ageing");
            case "acc-customer-statement"  -> statement(orgId, branch, "CUSTOMER", "AR", f.customerId(), from, to, "acc-customer-statement", "Customer Statement");
            case "acc-supplier-statement"  -> statement(orgId, branch, "SUPPLIER", "AP,SUPPLIER_ADVANCE", f.supplierId(), from, to, "acc-supplier-statement", "Supplier Statement");
            case "acc-cash-book"           -> book(orgId, branch, "CASH_REGISTER", from, to, "acc-cash-book", "Cash Book");
            case "acc-bank-book"           -> book(orgId, branch, "BANK_DEFAULT", from, to, "acc-bank-book", "Bank Book");
            case "acc-petty-cash-book"     -> book(orgId, branch, "PETTY_CASH", from, to, "acc-petty-cash-book", "Petty Cash Book");
            case "acc-daily-cash", "acc-branch-cash-book", "acc-cash-closing" -> dailyCash(orgId, branch, from, to, id);
            case "acc-cash-difference"     -> cashDifference(orgId, branch, from, to);
            case "acc-cash-reconciliation" -> cashReconciliation(orgId, branch, to);
            case "acc-investor-statement"  -> investorStatement(orgId, from, to);
            case "acc-investor-capital"    -> investorCapital(orgId, to);
            case "acc-investor-profit"     -> investorProfit(orgId, from, to);
            case "acc-profit-distribution" -> profitDistribution(orgId, from, to);
            case "acc-payroll-summary"     -> payrollSummary(orgId, branch, from, to);
            case "acc-salary-expense"      -> salaryExpense(orgId, branch, from, to);
            case "acc-tax"                 -> tax(orgId, branch, from, to);
            case "acc-events"              -> events(orgId, branch, from, to);
            default -> throw new ValidationException("'" + id + "' is not an accounting report.");
        };
    }

    private static final List<String> DATES = List.of("dateRange", "branch");

    /** Thousands-separated, two decimals — for the sentence under a report title. */
    private static String fmt(BigDecimal v) { return v == null ? "0.00" : new java.text.DecimalFormat("#,##0.00").format(v); }

    private ReportGridResponse trialBalance(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("code", "Code", "text"), col("name", "Account", "text"), col("type", "Type", "text"),
                money("opening", "Opening"), money("debit", "Debit"), money("credit", "Credit"), money("closingDebit", "Closing Dr"), money("closingCredit", "Closing Cr"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : ledger.trialBalance(org, branch, from, to))
            rows.add(row(cols, r.get("code"), r.get("name"), r.get("type"), r.get("opening"), r.get("debit"), r.get("credit"), r.get("closingDebit"), r.get("closingCredit")));
        return grid("acc-trial-balance", "Trial Balance", cols, rows, "Every posting account with a balance or movement. Closing Dr must equal Closing Cr in total.", DATES);
    }

    private ReportGridResponse profitLoss(Long org, Long branch, LocalDate from, LocalDate to, String id, String title) {
        List<Column> cols = List.of(col("group", "Section", "text"), col("code", "Code", "text"), col("name", "Account", "text"), money("amount", "Amount"));
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Map<String, Object> r : ledger.profitAndLoss(org, branch, from, to)) {
            BigDecimal a = (BigDecimal) r.get("amount");
            if ("REVENUE".equals(r.get("type"))) revenue = revenue.add(a); else expense = expense.add(a);
            rows.add(row(cols, r.get("group"), r.get("code"), r.get("name"), "REVENUE".equals(r.get("type")) ? a : a.negate()));
        }
        ReportGridResponse out = grid(id, title, cols, rows, "Revenue " + fmt(revenue) + " less expenses " + fmt(expense) + " = net " + (revenue.compareTo(expense) < 0 ? "loss " : "profit ") + fmt(revenue.subtract(expense).abs()) + ". Expenses are shown negative so the Amount column totals to the net result.", DATES);
        return out;
    }

    private ReportGridResponse balanceSheet(Long org, Long branch, LocalDate asOf) {
        List<Column> cols = List.of(col("type", "Section", "text"), col("group", "Group", "text"), col("code", "Code", "text"), col("name", "Account", "text"), money("amount", "Balance"));
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal assets = BigDecimal.ZERO, liab = BigDecimal.ZERO, eq = BigDecimal.ZERO;
        for (Map<String, Object> r : ledger.balanceSheet(org, branch, asOf)) {
            BigDecimal a = (BigDecimal) r.get("amount");
            switch ((String) r.get("type")) { case "ASSET" -> assets = assets.add(a); case "LIABILITY" -> liab = liab.add(a); default -> eq = eq.add(a); }
            rows.add(row(cols, r.get("type"), r.get("group"), r.get("code"), r.get("name"), "ASSET".equals(r.get("type")) ? a : a.negate()));
        }
        return grid("acc-balance-sheet", "Balance Sheet", cols, rows, "As at " + asOf + ". Assets " + fmt(assets) + " = liabilities " + fmt(liab) + " + equity " + fmt(eq) + " (difference " + fmt(assets.subtract(liab).subtract(eq)) + "). Liabilities and equity are shown negative so the column totals to zero when the books balance.", List.of("dateRange", "branch"));
    }

    private ReportGridResponse cashFlow(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("activity", "Activity", "text"), col("counterpart", "Counterpart", "text"), money("inflow", "Inflow"), money("outflow", "Outflow"), money("net", "Net"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : ledger.cashFlow(org, branch, from, to)) rows.add(row(cols, r.get("activity"), r.get("counterpart"), r.get("inflow"), r.get("outflow"), r.get("net")));
        return grid("acc-cash-flow", "Cash Flow", cols, rows, "Direct method: every movement on the till, wallets, petty cash and bank accounts, grouped by what it was for.", DATES);
    }

    private ReportGridResponse generalLedger(Long org, Long branch, ReportGridServiceImpl.Filters f, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("account", "Account", "text"), col("reference", "Reference", "text"),
                col("description", "Description", "text"), money("debit", "Debit"), money("credit", "Credit"), col("party", "Party", "text"), col("source", "Source", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        // Every posting account, or one when the search names a code.
        String q = f.search();
        List<AccAccount> accounts = chart.list(org).stream().filter(a -> !Integer.valueOf(1).equals(a.getIsGroup()))
                .filter(a -> q.isEmpty() || a.getAccountCode().equalsIgnoreCase(q) || a.getAccountName().toLowerCase().contains(q.toLowerCase())).toList();
        // One account is a proper ledger page: opening row, running balance, closing in the note.
        if (accounts.size() == 1) {
            AccAccount a = accounts.get(0);
            List<Column> one = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("reference", "Reference", "text"), col("description", "Description", "text"),
                    money("debit", "Debit"), money("credit", "Credit"), col("balance", "Balance", "money"), col("party", "Party", "text"), col("source", "Source", "text"));
            Map<String, Object> l = ledger.ledger(org, branch, a.getPidAccount(), from, to, null, null, null, 0, 5000);
            rows.add(row(one, from, "", "", "Opening balance", null, null, l.get("openingBalance"), "", ""));
            @SuppressWarnings("unchecked") List<Map<String, Object>> lr = (List<Map<String, Object>>) l.get("rows");
            for (Map<String, Object> r : lr) rows.add(row(one, r.get("date"), r.get("journalNo"), r.get("reference"), r.get("description"), r.get("debit"), r.get("credit"), r.get("balance"), r.get("partyName"), r.get("sourceModule")));
            return grid("acc-general-ledger", "General Ledger · " + a.getAccountCode() + " " + a.getAccountName(), one, rows,
                    "Opening " + fmt((BigDecimal) l.get("openingBalance")) + " + debit " + fmt((BigDecimal) l.get("totalDebit")) + " − credit " + fmt((BigDecimal) l.get("totalCredit")) + " = closing " + fmt((BigDecimal) l.get("closingBalance")) + " (" + ("D".equals(l.get("normalSide")) ? "debit" : "credit") + " balance).", List.of("dateRange", "branch", "search"));
        }
        for (AccAccount a : accounts) {
            Map<String, Object> l = ledger.ledger(org, branch, a.getPidAccount(), from, to, null, null, null, 0, 5000);
            @SuppressWarnings("unchecked") List<Map<String, Object>> lr = (List<Map<String, Object>>) l.get("rows");
            for (Map<String, Object> r : lr) rows.add(row(cols, r.get("date"), r.get("journalNo"), a.getAccountCode() + " " + a.getAccountName(), r.get("reference"), r.get("description"),
                    r.get("debit"), r.get("credit"), r.get("partyName"), r.get("sourceModule")));
            if (rows.size() > 5000) break;
        }
        return grid("acc-general-ledger", "General Ledger", cols, rows, "Type an account code or name in the search to see one account with its opening, running balance and closing.", List.of("dateRange", "branch", "search"));
    }

    private ReportGridResponse journalRegister(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("type", "Type", "text"), col("status", "Status", "text"),
                col("reference", "Reference", "text"), col("description", "Description", "text"), money("debit", "Debit"), money("credit", "Credit"), col("source", "Source", "text"), col("postedBy", "Posted by", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        journalRepo.search(org, branch, null, null, from, to, "", PageRequest.of(0, 5000)).forEach(j ->
                rows.add(row(cols, j.getJournalDate(), j.getJournalNo(), j.getJournalType(), j.getJournalStatus(), j.getReference(), j.getDescription(),
                        j.getTotalDebit(), j.getTotalCredit(), j.getSourceModule() + (j.getSourceType() == null ? "" : "/" + j.getSourceType()), j.getPostedBy())));
        return grid("acc-journal-register", "Journal Register", cols, rows, null, DATES);
    }

    private ReportGridResponse dailySummary(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), num("journals", "Journals"), money("revenue", "Revenue"), money("cogs", "Cost of sales"), money("expenses", "Expenses"),
                money("cashIn", "Cash in"), money("cashOut", "Cash out"));
        List<Map<String, Object>> rows = new ArrayList<>();
        AccAccount till = chart.byRole(org, "CASH_REGISTER");
        Long b = branch != null ? branch : scope.branchId(null);
        Map<LocalDate, Map<String, Object>> cash = new HashMap<>();
        ledger.dailyCashBook(b, till.getPidAccount(), from, to).forEach(r -> cash.put((LocalDate) r.get("date"), r));
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, BigDecimal> t = ledger.totalsByType(org, branch, d, d);
            long n = journalRepo.search(org, branch, "POSTED", null, d, d, "", PageRequest.of(0, 1)).getTotalElements();
            Map<String, Object> c = cash.get(d);
            if (n == 0 && c == null) continue;
            BigDecimal cogs = BigDecimal.ZERO, exp = BigDecimal.ZERO;
            for (Map<String, Object> r : ledger.profitAndLoss(org, branch, d, d)) { if ("COGS".equals(r.get("role"))) cogs = cogs.add((BigDecimal) r.get("amount")); else if ("EXPENSE".equals(r.get("type"))) exp = exp.add((BigDecimal) r.get("amount")); }
            rows.add(row(cols, d, n, t.getOrDefault("REVENUE", BigDecimal.ZERO), cogs, exp, c == null ? BigDecimal.ZERO : c.get("cashIn"), c == null ? BigDecimal.ZERO : c.get("cashOut")));
        }
        return grid("acc-daily-summary", "Daily Transaction Summary", cols, rows, null, DATES);
    }

    private ReportGridResponse branchPnl(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("branch", "Branch", "text"), money("revenue", "Revenue"), money("expense", "Expenses"), money("profit", "Net profit"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var b : branchRepo.findByPidOrgAndStatusOrderByBranchName(org, "Y")) {
            if (branch != null && !branch.equals(b.getPidBranch())) continue;
            Map<String, BigDecimal> t = ledger.totalsByType(org, b.getPidBranch(), from, to);
            BigDecimal rev = t.getOrDefault("REVENUE", BigDecimal.ZERO), exp = t.getOrDefault("EXPENSE", BigDecimal.ZERO);
            rows.add(row(cols, b.getBranchName(), rev, exp, rev.subtract(exp)));
        }
        return grid("acc-branch-pnl", "Branch-wise Profit & Loss", cols, rows, null, DATES);
    }

    /**
     * Revenue or expense by account, with its share of the total and the same
     * window immediately before it: a manager reading "rent 18%" wants to know
     * whether it was 18% last time too, and a second report to answer that is
     * one more thing to open.
     */
    /**
     * What was spent, day by day, by head and by cost centre.
     *
     * <p>NOT a duplicate of the two reports either side of it, which is worth
     * saying because a fourth expense report would be. The daily summary gives
     * one expense number a day and no breakdown; the expense analysis breaks a
     * window down by head with no days in it. Neither can answer "what did the
     * front shop spend on tea last week", which is the question a cost centre
     * exists for.
     *
     * <p>Postings made before anybody set a cost centre - which is all of them
     * until today - show as "(not set)" rather than being folded into a bucket
     * that would read like a real centre.
     */
    private ReportGridResponse dailyExpense(Long org, Long branch, LocalDate from, LocalDate to, Long costCenterId) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("centre", "Cost centre", "text"),
                col("code", "Code", "text"), col("name", "Expense head", "text"),
                col("postings", "Postings", "number"),
                /* Deliberately not money(), which would make the grid total the
                   column for itself: this report carries its own subtotal rows,
                   and a footer that summed those as well as the lines would read
                   three times the truth. */
                col("amount", "Amount", "money"));
        List<Map<String, Object>> src = ledger.dailyExpenses(org, branch, from, to, costCenterId);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        LocalDate day = null;
        BigDecimal dayTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : src) {
            LocalDate d = (LocalDate) r.get("date");
            if (day != null && !day.equals(d)) {
                rows.add(row(cols, null, "Total for " + day, null, null, null, dayTotal));
                dayTotal = BigDecimal.ZERO;
            }
            day = d;
            BigDecimal amt = (BigDecimal) r.get("amount");
            dayTotal = dayTotal.add(amt);
            total = total.add(amt);
            String centre = r.get("ccCode") == null ? "(not set)" : r.get("ccCode") + " " + r.get("ccName");
            rows.add(row(cols, d, centre, r.get("code"), r.get("name"), r.get("postings"), amt));
        }
        if (day != null) rows.add(row(cols, null, "Total for " + day, null, null, null, dayTotal));
        if (!rows.isEmpty()) rows.add(row(cols, null, "Total for the window", null, null, null, total));

        String note = "Every expense posting in the window, from the posted ledger, grouped by day, head and cost centre. "
                + "A posting with no cost centre shows as (not set): the dimension is optional, and nothing set it before it "
                + "was offered on the entry screens.";
        return grid("acc-daily-expense", "Daily Expense Report", cols, rows, note,
                List.of("dateRange", "branch", "costCenter"));
    }

    private ReportGridResponse analysis(Long org, Long branch, LocalDate from, LocalDate to, String type, String id, String title) {
        List<Column> cols = List.of(col("group", "Group", "text"), col("code", "Code", "text"), col("name", "Account", "text"), money("amount", "Amount"), col("share", "Share %", "number"),
                money("previous", "Previous period"), money("variance", "Variance"), col("variancePct", "Variance %", "number"));
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1), prevFrom = prevTo.minusDays(days - 1);
        List<Map<String, Object>> src = ledger.profitAndLoss(org, branch, from, to).stream().filter(r -> type.equals(r.get("type"))).toList();
        Map<String, BigDecimal> prev = new HashMap<>();
        for (Map<String, Object> r : ledger.profitAndLoss(org, branch, prevFrom, prevTo)) if (type.equals(r.get("type"))) prev.merge(String.valueOf(r.get("code")), (BigDecimal) r.get("amount"), BigDecimal::add);
        BigDecimal total = src.stream().map(r -> (BigDecimal) r.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : src) {
            BigDecimal now = (BigDecimal) r.get("amount"), before = prev.getOrDefault(String.valueOf(r.get("code")), BigDecimal.ZERO);
            rows.add(row(cols, r.get("group"), r.get("code"), r.get("name"), now, margin(now, total), before, now.subtract(before), margin(now.subtract(before), before)));
        }
        return grid(id, title, cols, rows, "Previous period is the " + days + " day" + (days == 1 ? "" : "s") + " before the window (" + prevFrom + " to " + prevTo + "). Variance % is against the previous period; blank where there was nothing to compare with.", DATES);
    }

    /**
     * A control account's roll-forward: what it opened at, what moved it, what it
     * closed at — one row per posting source, so "why did receivables rise" reads
     * as credit sales, collections, returns and adjustments in a single grid.
     *
     * <p>Balances and movement come from the posted ledger only. The AR and AP
     * versions end with the sub-ledger's own figure (open invoices net of
     * payments) and the difference, which is the reconciliation an auditor asks
     * for; the invoice tables carry no as-at history, so that tie-out is shown
     * only when the window ends today or later.
     */
    private ReportGridResponse controlMovement(Long org, Long branch, LocalDate from, LocalDate to, String role, String id, String title) {
        List<Column> cols = List.of(col("line", "Line", "text"), col("source", "Source", "text"), col("type", "Type", "text"), col("lines", "Lines", "number"),
                money("debit", "Debit"), money("credit", "Credit"), col("net", "Net movement", "money"), col("balance", "Balance", "money"));
        Optional<AccAccount> acc = chart.byRoleIfAny(org, role);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (acc.isEmpty()) return grid(id, title, cols, rows, "No account carries the " + role + " role in this chart.", DATES);
        AccAccount a = acc.get(); String side = a.getNormalSide();
        BigDecimal opening = ledger.accountBalance(branch, a.getPidAccount(), from.minusDays(1), side);
        BigDecimal run = opening;
        rows.add(row(cols, "Opening balance", a.getAccountCode() + " " + a.getAccountName(), "as at " + from.minusDays(1), null, null, null, null, opening));
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Map<String, Object> m : ledger.cashBySource(org, branch, a.getPidAccount(), from, to)) {
            BigDecimal d = (BigDecimal) m.get("cashIn"), c = (BigDecimal) m.get("cashOut");
            BigDecimal net = "D".equals(side) ? d.subtract(c) : c.subtract(d);
            run = run.add(net); dr = dr.add(d); cr = cr.add(c);
            rows.add(row(cols, "Movement", m.get("sourceModule"), m.get("sourceType"), m.get("count"), d, c, net, run));
        }
        rows.add(row(cols, "Closing balance", a.getAccountCode() + " " + a.getAccountName(), "as at " + to, null, null, null, "D".equals(side) ? dr.subtract(cr) : cr.subtract(dr), run));
        String note = "Opening + net movement = closing, from posted journals only" + (branch == null ? " across all branches" : "") + ". Debit and credit totals are the period movement; the balance column is read from the account's " + ("D".equals(side) ? "debit" : "credit") + " side.";
        if (("AR".equals(role) || "AP".equals(role)) && !to.isBefore(LocalDate.now())) {
            BigDecimal sub = BigDecimal.ZERO;
            if ("AR".equals(role)) for (Object[] r : invoices.salesByCustomer(org, branch, null, to, "", true)) sub = sub.add(bd(r[6]));
            else for (Object[] r : invoices.supplierOutstanding(org, branch, "")) sub = sub.add(bd(r[6])).subtract(bd(r[13]));
            rows.add(row(cols, "Sub-ledger", "AR".equals(role) ? "Customer Due (open invoices)" : "Supplier Outstanding (open invoices less unapplied payments)", "as at today", null, null, null, null, sub));
            rows.add(row(cols, "Difference", "ledger − sub-ledger", run.subtract(sub).signum() == 0 ? "reconciled" : "investigate", null, null, null, null, run.subtract(sub)));
            note += " The last two rows tie the control account to the invoices: a difference means a posting is missing, an invoice was edited after it posted, or the account also carries other parties (tenant subscriptions post to receivables too).";
        } else if ("AR".equals(role) || "AP".equals(role)) {
            note += " The invoice tie-out is shown only when the window ends today, because the invoice tables carry no as-at history.";
        }
        return grid(id, title, cols, rows, note, DATES);
    }

    private ReportGridResponse ageing(Long org, Long branch, LocalDate asOf, String partyType, String role, String id, String title) {
        List<Column> cols = List.of(col("party", "Party", "text"), money("outstanding", "Outstanding"), money("d0_30", "0–30 days"), money("d31_60", "31–60"), money("d61_90", "61–90"), money("d90plus", "Over 90"), col("lastActivity", "Last activity", "date"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : ledger.ageing(org, branch, partyType, role, asOf))
            rows.add(row(cols, r.get("partyName"), r.get("outstanding"), r.get("d0_30"), r.get("d31_60"), r.get("d61_90"), r.get("d90plus"), r.get("lastActivity")));
        return grid(id, title, cols, rows, "As at " + asOf + ", from the ledger. Each balance is aged against its most recent invoices.", List.of("dateRange", "branch"));
    }

    private ReportGridResponse statement(Long org, Long branch, String partyType, String role, Long partyId, LocalDate from, LocalDate to, String id, String title) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("reference", "Reference", "text"), col("description", "Description", "text"),
                money("debit", "Debit"), money("credit", "Credit"), col("balance", "Balance", "money"));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (partyId == null) {
            for (Map<String, Object> p : ledger.partyBalances(org, branch, partyType, role, to, true))
                rows.add(row(cols, p.get("lastActivity"), "", p.get("partyName"), "balance as at " + to, p.get("debit"), p.get("credit"), p.get("balance")));
            return grid(id, title, cols, rows, "Choose a " + partyType.toLowerCase() + " to see the statement; without one, every open balance is listed.", List.of("dateRange", "branch", partyType.equals("CUSTOMER") ? "customer" : "supplier"));
        }
        Map<String, Object> st = ledger.partyStatement(org, branch, partyType, partyId, role, from, to);
        rows.add(row(cols, from, "", "", "Opening balance", null, null, st.get("openingBalance")));
        @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) st.get("rows");
        // A statement over several accounts (payable + advances) says which one each line hit, so an
        // advance is recognisable as such and the net closing balance reads as owed / advance held.
        boolean mixed = role != null && role.contains(",");
        for (Map<String, Object> r : lines) {
            String desc = String.valueOf(r.get("description") == null ? "" : r.get("description"));
            if (mixed && r.get("account") != null) desc = desc + " · " + r.get("account");
            rows.add(row(cols, r.get("date"), r.get("journalNo"), r.get("reference"), desc, r.get("debit"), r.get("credit"), r.get("balance")));
        }
        java.math.BigDecimal closing = (java.math.BigDecimal) st.get("closingBalance");
        String meaning = !mixed || closing == null || closing.signum() == 0 ? "" : closing.signum() < 0 ? " (advance held by the " + partyType.toLowerCase() + ")" : " (owed to the " + partyType.toLowerCase() + ")";
        return grid(id, title, cols, rows, "Closing balance " + st.get("closingBalance") + meaning, List.of("dateRange", "branch", partyType.equals("CUSTOMER") ? "customer" : "supplier"));
    }

    private ReportGridResponse book(Long org, Long branch, String role, LocalDate from, LocalDate to, String id, String title) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("reference", "Reference", "text"), col("description", "Description", "text"),
                money("debit", "Receipt"), money("credit", "Payment"), col("balance", "Balance", "money"), col("source", "Source", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Optional<AccAccount> acc = chart.byRoleIfAny(org, role);
        if (acc.isEmpty()) return grid(id, title, cols, rows, "No account carries the role " + role + ".", DATES);
        Map<String, Object> l = ledger.ledger(org, branch, acc.get().getPidAccount(), from, to, null, null, null, 0, 5000);
        rows.add(row(cols, from, "", "", "Opening balance", null, null, l.get("openingBalance"), ""));
        @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) l.get("rows");
        for (Map<String, Object> r : lines) rows.add(row(cols, r.get("date"), r.get("journalNo"), r.get("reference"), r.get("description"), r.get("debit"), r.get("credit"), r.get("balance"), r.get("sourceModule")));
        return grid(id, title, cols, rows, acc.get().getAccountCode() + " " + acc.get().getAccountName() + " — closing balance " + l.get("closingBalance"), DATES);
    }

    private ReportGridResponse dailyCash(Long org, Long branch, LocalDate from, LocalDate to, String id) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("branch", "Branch", "text"), col("status", "Status", "text"), money("opening", "Opening"), money("cashIn", "Cash in"),
                money("cashOut", "Cash out"), money("expected", "Expected"), money("physical", "Counted"), money("difference", "Difference"), col("kind", "Result", "text"), col("closedBy", "Closed by", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AccBusinessDay> days = branch != null ? dayRepo.between(branch, from, to)
                : branchRepo.findByPidOrgAndStatusOrderByBranchName(org, "Y").stream().flatMap(b -> dayRepo.between(b.getPidBranch(), from, to).stream()).toList();
        for (AccBusinessDay d : days) rows.add(row(cols, d.getBusinessDate(), branchRepo.findById(d.getPidBranch()).map(b -> b.getBranchName()).orElse(""), d.getDayStatus(),
                d.getOpeningCash(), d.getCashIn(), d.getCashOut(), d.getExpectedClosing(), d.getPhysicalCash(), d.getDifference(), d.getDifferenceKind(), d.getClosedBy()));
        return grid(id, CATALOGUE.get(id), cols, rows, null, DATES);
    }

    private ReportGridResponse cashDifference(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("branch", "Branch", "text"), money("expected", "Expected"), money("physical", "Counted"), money("difference", "Difference"),
                col("kind", "Result", "text"), col("reason", "Reason", "text"), col("posted", "Variance posted", "text"), col("closedBy", "Closed by", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AccBusinessDay> days = branch != null ? dayRepo.between(branch, from, to)
                : branchRepo.findByPidOrgAndStatusOrderByBranchName(org, "Y").stream().flatMap(b -> dayRepo.between(b.getPidBranch(), from, to).stream()).toList();
        for (AccBusinessDay d : days) {
            if (d.getDifference() == null || d.getDifference().signum() == 0) continue;
            rows.add(row(cols, d.getBusinessDate(), branchRepo.findById(d.getPidBranch()).map(b -> b.getBranchName()).orElse(""), d.getExpectedClosing(), d.getPhysicalCash(),
                    d.getDifference(), d.getDifferenceKind(), d.getDifferenceReason(), Integer.valueOf(1).equals(d.getVariancePosted()) ? "Yes" : "No", d.getClosedBy()));
        }
        return grid("acc-cash-difference", "Cash Short / Over Report", cols, rows, null, DATES);
    }

    private ReportGridResponse cashReconciliation(Long org, Long branch, LocalDate asOf) {
        List<Column> cols = List.of(col("branch", "Branch", "text"), money("ledger", "Ledger till balance"), money("lastCounted", "Last counted"), col("lastCountDate", "Counted on", "date"),
                money("unpostedVariance", "Unposted variance"), col("unclosed", "Unclosed days", "number"));
        List<Map<String, Object>> rows = new ArrayList<>();
        AccAccount till = chart.byRole(org, "CASH_REGISTER");
        for (var b : branchRepo.findByPidOrgAndStatusOrderByBranchName(org, "Y")) {
            if (branch != null && !branch.equals(b.getPidBranch())) continue;
            BigDecimal bal = ledger.accountBalance(b.getPidBranch(), till.getPidAccount(), asOf, "D");
            Optional<AccBusinessDay> last = dayRepo.closedBefore(b.getPidBranch(), asOf.plusDays(1), PageRequest.of(0, 1)).stream().findFirst();
            BigDecimal unposted = BigDecimal.ZERO;
            for (AccBusinessDay d : dayRepo.between(b.getPidBranch(), LocalDate.of(2000, 1, 1), asOf))
                if (d.getDifference() != null && !Integer.valueOf(1).equals(d.getVariancePosted()) && "CLOSED".equals(d.getDayStatus())) unposted = unposted.add(d.getDifference());
            long unclosed = dayRepo.unclosedBefore(org, asOf).stream().filter(d -> d.getPidBranch().equals(b.getPidBranch())).count();
            rows.add(row(cols, b.getBranchName(), bal, last.map(AccBusinessDay::getPhysicalCash).orElse(null), last.map(AccBusinessDay::getBusinessDate).orElse(null), unposted, unclosed));
        }
        return grid("acc-cash-reconciliation", "Cash Reconciliation", cols, rows, "Ledger till balance versus the last counted closing; unposted variance is the sum of closing differences policy did not post.", List.of("dateRange", "branch"));
    }

    private ReportGridResponse investorStatement(Long org, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("investor", "Investor", "text"), col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("account", "Account", "text"),
                col("description", "Description", "text"), money("debit", "Debit"), money("credit", "Credit"), col("balance", "Balance", "money"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var i : investorRepo.findByPidOrgOrderByInvestorCodeAsc(org)) {
            Map<String, Object> st = ledger.partyStatement(org, null, "INVESTOR", i.getPidInvestor(), null, from, to);
            @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) st.get("rows");
            if (lines.isEmpty() && ((BigDecimal) st.get("openingBalance")).signum() == 0) continue;
            rows.add(row(cols, i.getInvestorName(), from, "", "", "Opening balance", null, null, st.get("openingBalance")));
            for (Map<String, Object> r : lines) rows.add(row(cols, i.getInvestorName(), r.get("date"), r.get("journalNo"), r.get("account"), r.get("description"), r.get("debit"), r.get("credit"), r.get("balance")));
        }
        return grid("acc-investor-statement", "Investor Statement", cols, rows, "Capital and profit-payable movements per investor, credit-normal: a positive balance is what the business holds for the investor.", List.of("dateRange"));
    }

    private ReportGridResponse investorCapital(Long org, LocalDate asOf) {
        List<Column> cols = List.of(col("code", "Code", "text"), col("investor", "Investor", "text"), col("status", "Status", "text"), col("sharePct", "Share %", "number"), money("capital", "Capital"), money("profitPayable", "Profit payable"));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, BigDecimal> cap = new HashMap<>(), pay = new HashMap<>();
        ledger.partyBalances(org, null, "INVESTOR", "INVESTOR_CAPITAL", asOf, false).forEach(r -> cap.put(((Number) r.get("partyId")).longValue(), (BigDecimal) r.get("balance")));
        ledger.partyBalances(org, null, "INVESTOR", "INVESTOR_PROFIT_PAYABLE", asOf, false).forEach(r -> pay.put(((Number) r.get("partyId")).longValue(), (BigDecimal) r.get("balance")));
        for (var i : investorRepo.findByPidOrgOrderByInvestorCodeAsc(org))
            rows.add(row(cols, i.getInvestorCode(), i.getInvestorName(), i.getInvestorStatus(), i.getSharePct(), cap.getOrDefault(i.getPidInvestor(), BigDecimal.ZERO), pay.getOrDefault(i.getPidInvestor(), BigDecimal.ZERO)));
        return grid("acc-investor-capital", "Investor Capital Report", cols, rows, "As at " + asOf, List.of("dateRange"));
    }

    private ReportGridResponse investorProfit(Long org, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("investor", "Investor", "text"), col("distribution", "Distribution", "text"), col("period", "Period", "text"), money("opening", "Opening capital"), money("additions", "Additions"),
                money("withdrawals", "Withdrawals"), money("eligible", "Eligible capital"), col("sharePct", "Share %", "number"), money("allocated", "Allocated"), money("paid", "Paid"), money("outstanding", "Outstanding"), col("status", "Status", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccProfitDistribution d : distRepo.findByPidOrgOrderByPeriodFromDescPidDistributionDesc(org)) {
            if (d.getPeriodTo().isBefore(from) || d.getPeriodFrom().isAfter(to) || "CANCELLED".equals(d.getDistStatus())) continue;
            for (AccProfitAllocation a : allocationRepo.findByPidDistributionOrderByPidAllocationAsc(d.getPidDistribution())) {
                String name = investorRepo.findById(a.getPidInvestor()).map(i -> i.getInvestorName()).orElse("");
                rows.add(row(cols, name, d.getDistributionNo(), d.getPeriodFrom() + " – " + d.getPeriodTo(), a.getOpeningCapital(), a.getAdditions(), a.getWithdrawals(), a.getEligibleCapital(),
                        a.getSharePct(), a.getAllocatedAmount(), a.getPaidAmount(), a.getAllocatedAmount().subtract(a.getPaidAmount()), a.getAllocStatus()));
            }
        }
        return grid("acc-investor-profit", "Investor Profit Statement", cols, rows, null, List.of("dateRange"));
    }

    private ReportGridResponse profitDistribution(Long org, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("distribution", "Distribution", "text"), col("period", "Period", "text"), col("method", "Method", "text"), col("status", "Status", "text"), money("revenue", "Revenue"),
                money("cogs", "Cost of sales"), money("expenses", "Expenses"), money("profit", "Accounting profit"), money("retained", "Retained"), money("adjustment", "Adjustment"),
                money("distributable", "Distributable"), money("allocated", "Allocated"), money("paid", "Paid"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccProfitDistribution d : distRepo.findByPidOrgOrderByPeriodFromDescPidDistributionDesc(org)) {
            if (d.getPeriodTo().isBefore(from) || d.getPeriodFrom().isAfter(to)) continue;
            rows.add(row(cols, d.getDistributionNo(), d.getPeriodFrom() + " – " + d.getPeriodTo(), d.getShareMethod(), d.getDistStatus(), d.getRevenue(), d.getCostOfSales(),
                    d.getOperatingExpenses().add(d.getOtherExpense()), d.getAccountingProfit(), d.getRetainedAmount(), d.getAdjustmentAmount(), d.getDistributableProfit(), d.getAllocatedTotal(), d.getPaidTotal()));
        }
        return grid("acc-profit-distribution", "Profit Distribution Report", cols, rows, null, List.of("dateRange"));
    }

    private ReportGridResponse payrollSummary(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("reference", "Payroll", "text"), col("account", "Account", "text"), money("debit", "Debit"), money("credit", "Credit"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String role : List.of("SALARY_EXPENSE", "PF_EXPENSE", "SALARY_PAYABLE", "PF_PAYABLE", "TAX_PAYABLE", "EMPLOYEE_ADVANCE", "EMPLOYEE_LOAN", "PAYROLL_OTHER_DED")) {
            Optional<AccAccount> a = chart.byRoleIfAny(org, role);
            if (a.isEmpty()) continue;
            Map<String, Object> l = ledger.ledger(org, branch, a.get().getPidAccount(), from, to, null, "HR", null, 0, 5000);
            @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) l.get("rows");
            for (Map<String, Object> r : lines) rows.add(row(cols, r.get("date"), r.get("journalNo"), r.get("reference"), a.get().getAccountName(), r.get("debit"), r.get("credit")));
        }
        return grid("acc-payroll-summary", "Payroll Summary (ledger)", cols, rows, "Every payroll-related posting: cost, payable, deductions and payments.", DATES);
    }

    private ReportGridResponse salaryExpense(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("month", "Month", "text"), money("salary", "Salaries & wages"), money("pf", "Provident fund"), money("gratuity", "Gratuity"), money("staff", "Staff expenses"), money("total", "Total"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
            LocalDate a = m.isBefore(from) ? from : m, b = m.plusMonths(1).minusDays(1).isAfter(to) ? to : m.plusMonths(1).minusDays(1);
            Map<String, BigDecimal> byRole = new HashMap<>();
            for (Map<String, Object> r : ledger.profitAndLoss(org, branch, a, b)) if (r.get("role") != null) byRole.merge((String) r.get("role"), (BigDecimal) r.get("amount"), BigDecimal::add);
            BigDecimal s = byRole.getOrDefault("SALARY_EXPENSE", BigDecimal.ZERO), pf = byRole.getOrDefault("PF_EXPENSE", BigDecimal.ZERO), g = byRole.getOrDefault("GRATUITY_EXPENSE", BigDecimal.ZERO), st = byRole.getOrDefault("STAFF_EXPENSE", BigDecimal.ZERO);
            if (s.add(pf).add(g).add(st).signum() == 0) continue;
            rows.add(row(cols, m.getYear() + "-" + String.format("%02d", m.getMonthValue()), s, pf, g, st, s.add(pf).add(g).add(st)));
        }
        return grid("acc-salary-expense", "Salary Expense", cols, rows, null, DATES);
    }

    private ReportGridResponse tax(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("journalNo", "Journal", "text"), col("account", "Account", "text"), col("reference", "Reference", "text"), money("debit", "Paid / cleared"), money("credit", "Charged"), col("balance", "Balance", "money"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String role : List.of("TAX_PAYABLE", "VAT_PAYABLE")) {
            Optional<AccAccount> a = chart.byRoleIfAny(org, role);
            if (a.isEmpty()) continue;
            Map<String, Object> l = ledger.ledger(org, branch, a.get().getPidAccount(), from, to, null, null, null, 0, 5000);
            @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) l.get("rows");
            for (Map<String, Object> r : lines) rows.add(row(cols, r.get("date"), r.get("journalNo"), a.get().getAccountName(), r.get("reference"), r.get("debit"), r.get("credit"), r.get("balance")));
        }
        return grid("acc-tax", "Tax / VAT Report", cols, rows, "Tax accounts from the ledger. Rates are configured under Periods & Settings › Tax.", DATES);
    }

    private ReportGridResponse events(Long org, Long branch, LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", "date"), col("module", "Module", "text"), col("type", "Event", "text"), col("reference", "Reference", "text"), col("status", "Status", "text"), money("amount", "Amount"), col("journal", "Journal", "text"), col("error", "Error", "text"));
        List<Map<String, Object>> rows = new ArrayList<>();
        eventRepo.monitor(org, branch, null, null, from, to, PageRequest.of(0, 5000)).forEach(e ->
                rows.add(row(cols, e.getEventDate(), e.getSourceModule(), e.getEventType(), e.getReference(), e.getEventStatus(), e.getAmount(),
                        e.getPidJournal() == null ? "" : journalRepo.findById(e.getPidJournal()).map(j -> j.getJournalNo()).orElse(""), e.getErrorMessage())));
        return grid("acc-events", "Accounting Event Register", cols, rows, null, DATES);
    }
}
