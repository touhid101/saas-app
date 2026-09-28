package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccAccount;
import com.friendspharma.app.entity.AccBusinessDay;
import com.friendspharma.app.entity.AccProfitDistribution;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Accounts dashboard and the financial control centre: every tile a
 * ledger figure, every warning a count of something that needs a person.
 */
@Service
@RequiredArgsConstructor
public class AccDashboardService {

    private final AccScope                 scope;
    private final AccLedgerService         ledger;
    private final AccChartService          chart;
    private final AccDailyCashService      dailyCash;
    private final AccEventRepository       eventRepo;
    private final AccJournalRepository     journalRepo;
    private final AccBusinessDayRepository dayRepo;
    private final AccProfitDistributionRepository distRepo;
    private final AccPeriodRepository      periodRepo;
    private final AllBranchRepository      branchRepo;
    private final AccLedgerVersion         ledgerVersion;

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(Long branchId, LocalDate from, LocalDate to) {
        scope.require(AccScope.MOD_DASHBOARD, PermissionGuard.Right.VIEW, "see the accounts dashboard");
        Long org = scope.orgId();
        chart.ensureProvisioned(org);
        Long branch = scope.branchFilter(branchId);
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1), t = to != null ? to : LocalDate.now();
        long days = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1;
        LocalDate pf = f.minusDays(days), pt = f.minusDays(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branch); out.put("from", f); out.put("to", t);

        /* Two passes over the ledger feed every tile: one as at the end date,
           grouped by branch and account, and one over this window and the one
           before it. The page used to ask fourteen separate all-time questions
           — by role, per branch, per bank account, per party type twice — and
           each was a scan of every journal line; on a million lines that was
           seven seconds. Now the scan runs once and the tiles are sums over a
           few hundred rows. */
        Map<Long, AccAccount> accounts = new HashMap<>();
        for (AccAccount a : chart.list(org)) accounts.put(a.getPidAccount(), a);
        Snapshot snap = snapshot(org, branch, pf, f, t);
        List<Object[]> asAt = snap.asAt;
        List<Object[]> window = snap.window;

        // Balance by posting role, signed by the account's normal side.
        Map<String, BigDecimal> bal = new LinkedHashMap<>();
        Map<Long, BigDecimal> tillByBranch = new HashMap<>();
        BigDecimal bank = BigDecimal.ZERO;
        for (Object[] r : asAt) {
            AccAccount a = accounts.get(((Number) r[1]).longValue());
            if (a == null) continue;
            BigDecimal v = AccLedgerService.signed(a.getNormalSide(), (BigDecimal) r[2], (BigDecimal) r[3]);
            if (a.getRoleKey() != null) bal.merge(a.getRoleKey(), v, BigDecimal::add);
            if ("CASH_REGISTER".equals(a.getRoleKey()) && r[0] != null) tillByBranch.merge(((Number) r[0]).longValue(), v, BigDecimal::add);
            // Bank accounts beyond the default carry no role; they are read by code range.
            if ("BANK_DEFAULT".equals(a.getRoleKey())
                    || (a.getAccountCode() != null && a.getAccountCode().startsWith("113") && Integer.valueOf(0).equals(a.getIsGroup())))
                bank = bank.add(AccLedgerService.signed("D", (BigDecimal) r[2], (BigDecimal) r[3]));
        }

        // Window totals: by account type and by account, for this window (cur = 1) and the one before.
        Map<String, BigDecimal>[] byType = new Map[]{ new HashMap<>(), new HashMap<>() };
        Map<Long, BigDecimal>[] byAccount = new Map[]{ new HashMap<>(), new HashMap<>() };
        Map<Long, BigDecimal> revenueByBranch = new HashMap<>(), expenseByBranch = new HashMap<>();
        for (Object[] r : window) {
            AccAccount a = accounts.get(((Number) r[1]).longValue());
            if (a == null) continue;
            int cur = ((Number) r[2]).intValue();
            String type = a.getAccountType();
            String typeSide = Set.of("ASSET", "EXPENSE").contains(type) ? "D" : "C";
            BigDecimal byTypeV = AccLedgerService.signed(typeSide, (BigDecimal) r[3], (BigDecimal) r[4]);
            byType[cur].merge(type, byTypeV, BigDecimal::add);
            BigDecimal amount = AccLedgerService.signed(a.getNormalSide(), (BigDecimal) r[3], (BigDecimal) r[4]);
            // Contra-revenue (Sales Returns, normal side D under REVENUE) shows negative within revenue.
            if ("REVENUE".equals(type) && "D".equals(a.getNormalSide())) amount = amount.negate();
            byAccount[cur].merge(a.getPidAccount(), amount, BigDecimal::add);
            if (cur == 1 && r[0] != null) {
                Long b = ((Number) r[0]).longValue();
                if ("REVENUE".equals(type)) revenueByBranch.merge(b, byTypeV, BigDecimal::add);
                if ("EXPENSE".equals(type)) expenseByBranch.merge(b, byTypeV, BigDecimal::add);
            }
        }
        BigDecimal revenue = byType[1].getOrDefault("REVENUE", BigDecimal.ZERO), expense = byType[1].getOrDefault("EXPENSE", BigDecimal.ZERO);
        BigDecimal cogs = roleTotal(byAccount[1], accounts, "COGS");
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("revenue", revenue); kpi.put("expenses", expense); kpi.put("costOfSales", cogs);
        kpi.put("grossProfit", revenue.subtract(cogs)); kpi.put("netProfit", revenue.subtract(expense));
        kpi.put("receivable", bal.getOrDefault("AR", BigDecimal.ZERO));
        kpi.put("payable", bal.getOrDefault("AP", BigDecimal.ZERO));
        kpi.put("cash", bal.getOrDefault("CASH_REGISTER", BigDecimal.ZERO));
        kpi.put("bank", bank);
        kpi.put("mobileWallet", bal.getOrDefault("MOBILE_WALLET", BigDecimal.ZERO));
        kpi.put("pettyCash", bal.getOrDefault("PETTY_CASH", BigDecimal.ZERO));
        kpi.put("cashInTransit", bal.getOrDefault("CASH_IN_TRANSIT", BigDecimal.ZERO));
        kpi.put("inventory", bal.getOrDefault("INVENTORY", BigDecimal.ZERO));
        kpi.put("payrollPayable", bal.getOrDefault("SALARY_PAYABLE", BigDecimal.ZERO).add(bal.getOrDefault("PF_PAYABLE", BigDecimal.ZERO)).add(bal.getOrDefault("EMPLOYEE_REIMB_PAYABLE", BigDecimal.ZERO)));
        kpi.put("investorCapital", bal.getOrDefault("INVESTOR_CAPITAL", BigDecimal.ZERO));
        kpi.put("investorProfitPayable", bal.getOrDefault("INVESTOR_PROFIT_PAYABLE", BigDecimal.ZERO));
        kpi.put("taxPayable", bal.getOrDefault("TAX_PAYABLE", BigDecimal.ZERO).add(bal.getOrDefault("VAT_PAYABLE", BigDecimal.ZERO)));
        kpi.put("supplierAdvances", bal.getOrDefault("SUPPLIER_ADVANCE", BigDecimal.ZERO));
        kpi.put("employeeAdvances", bal.getOrDefault("EMPLOYEE_ADVANCE", BigDecimal.ZERO).add(bal.getOrDefault("EMPLOYEE_LOAN", BigDecimal.ZERO)));
        out.put("kpi", kpi);

        // The same window immediately before this one, so every period tile can say how it moved.
        BigDecimal prevRevenue = byType[0].getOrDefault("REVENUE", BigDecimal.ZERO), prevExpense = byType[0].getOrDefault("EXPENSE", BigDecimal.ZERO);
        BigDecimal prevCogs = roleTotal(byAccount[0], accounts, "COGS");
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("from", pf); previous.put("to", pt);
        previous.put("revenue", prevRevenue); previous.put("expenses", prevExpense); previous.put("costOfSales", prevCogs);
        previous.put("grossProfit", prevRevenue.subtract(prevCogs)); previous.put("netProfit", prevRevenue.subtract(prevExpense));
        out.put("previous", previous);

        // One trend query over both windows, split on the date.
        List<Map<String, Object>> trend = new ArrayList<>(), previousTrend = new ArrayList<>();
        for (Map<String, Object> d : snap.trend) {
            LocalDate day = (LocalDate) d.get("date");
            (day.isBefore(f) ? previousTrend : trend).add(d);
        }
        out.put("trend", trend);
        out.put("previousTrend", previousTrend);

        // Ageing of what is owed to us and what we owe, summed over parties —
        // and the same lists give the largest balances further down.
        List<Map<String, Object>> customers = snap.customers;
        List<Map<String, Object>> suppliers = snap.suppliers;
        Map<String, Object> ageing = new LinkedHashMap<>();
        ageing.put("customers", sumBuckets(customers));
        ageing.put("suppliers", sumBuckets(suppliers));
        out.put("ageing", ageing);

        // With no branch filter, the same result per branch so the reader can compare and drill in.
        if (branch == null) {
            List<Map<String, Object>> branches = new ArrayList<>();
            for (var b : branchRepo.findByPidOrgAndStatusOrderByBranchName(org, "Y")) {
                BigDecimal rv = revenueByBranch.getOrDefault(b.getPidBranch(), BigDecimal.ZERO), ex = expenseByBranch.getOrDefault(b.getPidBranch(), BigDecimal.ZERO);
                BigDecimal till = tillByBranch.getOrDefault(b.getPidBranch(), BigDecimal.ZERO);
                if (rv.signum() == 0 && ex.signum() == 0 && till.signum() == 0) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("branchId", b.getPidBranch()); row.put("branchName", b.getBranchName());
                row.put("revenue", rv); row.put("expenses", ex); row.put("netProfit", rv.subtract(ex)); row.put("cash", till);
                branches.add(row);
            }
            branches.sort((a, b2) -> ((BigDecimal) b2.get("netProfit")).compareTo((BigDecimal) a.get("netProfit")));
            out.put("branches", branches);
        }

        // Where the money went: the expense accounts of the period, largest first.
        List<Map<String, Object>> expenses = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : byAccount[1].entrySet()) {
            AccAccount a = accounts.get(e.getKey());
            if (a == null || !"EXPENSE".equals(a.getAccountType()) || e.getValue().signum() == 0) continue;
            AccAccount parent = a.getPidParent() == null ? null : accounts.get(a.getPidParent());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("accountId", a.getPidAccount()); r.put("code", a.getAccountCode()); r.put("name", a.getAccountName());
            r.put("type", a.getAccountType()); r.put("group", parent == null ? null : parent.getAccountName()); r.put("role", a.getRoleKey());
            r.put("amount", e.getValue());
            expenses.add(r);
        }
        expenses.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));
        out.put("expenseTop", expenses.stream().limit(8).toList());

        // Today's cash, for the session branch (a branch-level control, so never "all branches").
        Long cashBranch = branch != null ? branch : scope.branchId(null);
        if (scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.VIEW)) out.put("todayCash", dailyCash.sheet(cashBranch, LocalDate.now()));

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("failedEvents", eventRepo.countByPidOrgAndEventStatus(org, "FAILED"));
        control.put("skippedEvents", eventRepo.countByPidOrgAndEventStatus(org, "SKIPPED"));
        control.put("draftJournals", journalRepo.search(org, branch, "DRAFT", null, LocalDate.of(2000, 1, 1), LocalDate.now().plusYears(1), "", PageRequest.of(0, 1)).getTotalElements());
        List<AccBusinessDay> unclosed = dayRepo.unclosedBefore(org, LocalDate.now()).stream().filter(d -> branch == null || d.getPidBranch().equals(branch)).toList();
        control.put("unclosedDays", unclosed.size());
        control.put("unclosedDayDates", unclosed.stream().map(d -> d.getBusinessDate().toString()).limit(10).toList());
        BigDecimal shortages = BigDecimal.ZERO, overages = BigDecimal.ZERO;
        for (AccBusinessDay d : dayRepo.findByPidOrgAndDayStatusOrderByBusinessDateDesc(org, "CLOSED")) {
            if (branch != null && !d.getPidBranch().equals(branch)) continue;
            if (d.getBusinessDate().isBefore(f) || d.getBusinessDate().isAfter(t) || d.getDifference() == null) continue;
            if (d.getDifference().signum() < 0) shortages = shortages.add(d.getDifference().abs()); else overages = overages.add(d.getDifference());
        }
        control.put("cashShortages", shortages); control.put("cashOverages", overages);
        long pendingDist = distRepo.findByPidOrgOrderByPeriodFromDescPidDistributionDesc(org).stream()
                .filter(d -> Set.of("CALCULATED", "REVIEWED", "APPROVED").contains(d.getDistStatus())).count();
        control.put("pendingDistributions", pendingDist);
        control.put("currentPeriod", periodRepo.findCovering(org, LocalDate.now()).map(p -> Map.of("name", p.getPeriodName(), "status", p.getPeriodStatus())).orElse(null));
        control.put("openPeriods", periodRepo.findByPidOrgOrderByStartDateDesc(org).stream().filter(p -> "OPEN".equals(p.getPeriodStatus()) && p.getEndDate().isBefore(LocalDate.now().withDayOfMonth(1))).count());
        out.put("control", control);

        out.put("recentJournals", journalRepo.search(org, branch, null, null, LocalDate.now().minusDays(7), LocalDate.now(), "", PageRequest.of(0, 8)).getContent());
        out.put("receivablesTop", topBalances(customers));
        out.put("payablesTop", topBalances(suppliers));
        return out;
    }

    /** The five ledger reads a dashboard is drawn from, as read. */
    private record Snapshot(long version, long readAt, List<Object[]> asAt, List<Object[]> window,
                            List<Map<String, Object>> trend, List<Map<String, Object>> customers, List<Map<String, Object>> suppliers) {}

    /** Kept per organisation, branch and window until the ledger changes or five minutes pass. */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private static final long SNAPSHOT_TTL_MS = 5 * 60 * 1000L;

    /**
     * The ledger as the dashboard needs it, read once and kept.
     *
     * <p>Reading the whole ledger is the cost of this page — a second and more
     * on a million lines — and the page polls every minute. The reads are kept
     * against the organisation's ledger version, which moves on every journal
     * written, so a repeat is free while nobody posts and never stale once
     * somebody does. The five reads do not depend on one another, so they run
     * side by side on their own connections and the page waits for the slowest
     * rather than the sum; the pool is small and shared, so a dozen people
     * opening the dashboard at once queue here instead of draining the
     * connection pool.
     */
    private Snapshot snapshot(Long org, Long branch, LocalDate pf, LocalDate f, LocalDate t) {
        String key = org + "|" + branch + "|" + pf + "|" + f + "|" + t;
        long version = ledgerVersion.get(org);
        Snapshot have = snapshots.get(key);
        if (have != null && have.version == version && System.currentTimeMillis() - have.readAt < SNAPSHOT_TTL_MS) return have;

        Future<List<Object[]>> fAsAt = READS.submit(() -> ledger.sumsByBranchAndAccount(org, branch, t));
        Future<List<Object[]>> fWindow = READS.submit(() -> ledger.windowSumsByBranchAndAccount(org, branch, pf, f, t));
        Future<List<Map<String, Object>>> fTrend = READS.submit(() -> ledger.dailyTrend(org, branch, pf, t));
        Future<List<Map<String, Object>>> fCustomers = READS.submit(() -> ledger.ageing(org, branch, "CUSTOMER", "AR", t));
        Future<List<Map<String, Object>>> fSuppliers = READS.submit(() -> ledger.ageing(org, branch, "SUPPLIER", "AP", t));
        Snapshot read = new Snapshot(version, System.currentTimeMillis(), await(fAsAt), await(fWindow), await(fTrend), await(fCustomers), await(fSuppliers));
        if (snapshots.size() > 200) snapshots.clear();   // many windows browsed: start over rather than grow without bound
        snapshots.put(key, read);
        return read;
    }

    /** Four threads for the ledger reads every dashboard fans out to; daemon, so they never hold a shutdown. */
    private static final ExecutorService READS = Executors.newFixedThreadPool(4, r -> { Thread th = new Thread(r, "acc-dashboard-read"); th.setDaemon(true); return th; });

    /** Waits for a ledger read, rethrowing what it threw rather than wrapping it. */
    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the ledger", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The five largest open balances of a party list, in the shape the party endpoint returns them. */
    private static List<Map<String, Object>> topBalances(List<Map<String, Object>> parties) {
        return parties.stream()
                .filter(p -> ((BigDecimal) p.get("balance")).signum() != 0)
                .sorted((a, b) -> ((BigDecimal) b.get("balance")).compareTo((BigDecimal) a.get("balance")))
                .limit(5)
                .map(p -> { Map<String, Object> m = new LinkedHashMap<>(p); m.keySet().removeAll(List.of("outstanding", "d0_30", "d31_60", "d61_90", "d90plus")); return m; })
                .toList();
    }

    /** The window total of every account carrying a role. */
    private static BigDecimal roleTotal(Map<Long, BigDecimal> byAccount, Map<Long, AccAccount> accounts, String role) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> e : byAccount.entrySet()) {
            AccAccount a = accounts.get(e.getKey());
            if (a != null && role.equals(a.getRoleKey())) total = total.add(e.getValue());
        }
        return total;
    }

    /** The four ageing buckets and the total, summed over every party in the list. */
    private static Map<String, BigDecimal> sumBuckets(List<Map<String, Object>> parties) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (String k : List.of("outstanding", "d0_30", "d31_60", "d61_90", "d90plus")) {
            BigDecimal t = BigDecimal.ZERO;
            for (Map<String, Object> pRow : parties) { Object v = pRow.get(k); if (v instanceof BigDecimal b) t = t.add(b); }
            m.put(k, t);
        }
        m.put("parties", BigDecimal.valueOf(parties.size()));
        return m;
    }
}
