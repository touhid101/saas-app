package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.DashboardResponse;
import com.friendspharma.app.dto.response.DashboardResponse.*;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.DashboardAnalyticsRepository;
import com.friendspharma.app.repository.PosSupplierPaymentRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * The dashboard.
 *
 * <p><b>Two dashboards, one endpoint.</b> Everyone gets the operational half:
 * what is short, what is dated, what is waiting to go out. The financial half is
 * built only for a user entitled to it, and for everyone else the field comes
 * back null having never been queried. That is the difference between a
 * dashboard that hides money and one that does not fetch it — only the second
 * survives somebody opening the network tab.
 *
 * <p><b>Everything is branch-scoped.</b> Where several pharmacies share the
 * application, a dashboard that mixed them would be worse than useless: one
 * shop's empty shelf hidden behind another's surplus. Scope comes from
 * {@link BranchScopeService}, the same rule the rest of the application uses.
 *
 * <p><b>Nothing here is estimated.</b> The screen this replaced multiplied
 * revenue by 0.18 and called it gross profit, then multiplied that by 0.65 and
 * called it net. Margin here is revenue minus the cost of the batches that
 * actually went out, and a figure that cannot be computed is reported as absent
 * rather than filled in with something plausible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl {

    private final DashboardAnalyticsRepository repo;
    private final PosSupplierPaymentRepository supplierRepo;
    private final AllBranchRepository branchRepo;
    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;

    /** Stock idle this long, with pieces on the shelf, is capital not inventory. */
    private static final int DEAD_STOCK_DAYS = 60;
    private static final int EXPIRY_HORIZON  = 90;

    /**
     * The dashboard for whoever is signed in.
     *
     * <p>There is no branch argument on purpose. A branch is a pharmacy and each
     * one runs its own trade, so the only board that means anything is the one
     * for the shop the user is standing in. {@code resolve(null)} takes the
     * branch off the session and refuses if the account has none.
     */
    @Transactional(readOnly = true)
    public Dashboard load(int days) {
        Long orgId  = SecurityUtils.getCurrentOrgId();
        Long branch = branchScope.resolve(null);
        boolean money = canSeeFinancials();

        Operations ops = operations(orgId, branch, days);

        return Dashboard.builder()
                .scope(Scope.builder()
                        .branchId(branch)
                        .branchName(branchRepo.findById(branch)
                                .map(b -> b.getBranchName()).orElse(null))
                        .days(days)
                        .canSeeFinancials(money)
                        .asOf(LocalDate.now())
                        .build())
                .operations(ops)
                // Not merely hidden: for anyone else the queries below never run.
                .finance(money ? finance(orgId, branch, days) : null)
                .build();
    }

    /**
     * Who may see money.
     *
     * <p><b>The Approve tick on the Dashboard in the RBAC screen</b>, not a role
     * named here. This used to read
     * {@code hasRole("ROLE01") || hasRole("ROLE04")}, which disagreed with the
     * matrix in both directions at once: Administrator (Branch) held
     * CAN_APPROVE on Dashboard and was refused anyway, while Accounts did not
     * hold it and was let in. Changing who may see takings also meant changing
     * Java and redeploying.
     *
     * <p>Now the tick is the permission, for any role, with no code change.
     *
     * <p>Deliberately Approve and not View: everyone who can open the dashboard
     * holds View on it — that is what puts it in their menu — so keying money to
     * View would show takings to the whole pharmacy.
     */
    public boolean canSeeFinancials() {
        return permissions.hasByRole(DASHBOARD_MODULE, PermissionGuard.Right.APPROVE);
    }

    /** Dashboard — the module whose Approve tick governs the money figures. */
    private static final String DASHBOARD_MODULE = "MOD01";

    /* ══════════════════════════════════════════════════════════════════════
       OPERATIONS
       ══════════════════════════════════════════════════════════════════════ */

    private Operations operations(Long orgId, Long branch, int days) {
        Object[] h = first(repo.stockHealth(orgId, branch));
        StockHealth stock = StockHealth.builder()
                .productsTracked(lng(h, 0)).outOfStock(lng(h, 1)).lowStock(lng(h, 2))
                .expiring30(lng(h, 3)).expiring90(lng(h, 4)).alreadyExpired(lng(h, 5))
                .deadStock(lng(h, 6))
                .piecesOnHand(dec(h, 7)).piecesExpiring90(dec(h, 8))
                .build();

        /* stages */
        long confirmed = 0, assigned = 0, onTheWay = 0, delivered = 0, collected = 0,
             unassigned = 0, raisedToday = 0, deliveredToday = 0, pending = 0;
        for (Object[] r : repo.ordersByStage(orgId, branch, days)) {
            String stage = str(r[0]);
            long n = num(r[1]), today = num(r[2]), noDm = num(r[3]);
            raisedToday += today;
            switch (stage == null ? "" : stage) {
                case "Confirmed"         -> { confirmed = n; pending += n; unassigned += noDm; }
                case "Delivery Assigned" -> { assigned = n;  pending += n; }
                case "On The Way"        -> { onTheWay = n;  pending += n; }
                case "Delivered"         -> { delivered = n; deliveredToday += today; }
                case "Collected"         -> { collected = n; deliveredToday += today; }
                case "Submitted"         -> pending += n;
                default -> { }
            }
        }

        List<DayPoint> trend = new ArrayList<>();
        BigDecimal piecesToday = BigDecimal.ZERO;
        for (Object[] r : repo.dailyActivity(orgId, branch, days)) {
            LocalDate day = date(r[0]);
            DayPoint p = DayPoint.builder().day(day).orders(num(r[1])).pcs(dec(r[2], 2)).build();
            trend.add(p);
            if (LocalDate.now().equals(day)) piecesToday = p.getPcs();
        }

        List<Mover> movers = new ArrayList<>();
        for (Object[] r : head(repo.topMoversByQuantity(orgId, branch, days), 8)) {
            BigDecimal pcs = dec(r[2], 2);
            BigDecimal recent = dec(r[3], 2);
            BigDecimal onHand = dec(r[4], 2);
            BigDecimal perDay = pcs.divide(BigDecimal.valueOf(Math.max(days, 1)), 4, RoundingMode.HALF_UP);
            // The second half of the window against the first: a product selling
            // the same amount in half the time is accelerating.
            BigDecimal earlier = pcs.subtract(recent);
            movers.add(Mover.builder()
                    .productName(str(r[0])).companyName(str(r[1]))
                    .pcs(pcs).onHand(onHand)
                    .daysOfCover(perDay.signum() <= 0 ? null
                            : onHand.divide(perDay, 0, RoundingMode.FLOOR).intValue())
                    .trendPct(earlier.signum() <= 0 ? null
                            : scale(recent.subtract(earlier).multiply(BigDecimal.valueOf(100))
                                    .divide(earlier, 4, RoundingMode.HALF_UP), 0))
                    .build());
        }

        List<Expiring> expiring = new ArrayList<>();
        for (Object[] r : head(repo.expiringBatches(orgId, branch, EXPIRY_HORIZON), 10)) {
            expiring.add(Expiring.builder()
                    .productName(str(r[0])).batchNo(str(r[1]))
                    .expiryDate(date(r[2])).pcs(dec(r[3], 0))
                    .daysLeft((int) num(r[4]))
                    .build());
        }

        return Operations.builder()
                .today(Today.builder()
                        .ordersRaised(raisedToday)
                        .ordersDelivered(deliveredToday)
                        .ordersPending(pending)
                        .piecesSold(piecesToday)
                        .build())
                .stock(stock)
                .delivery(Delivery.builder()
                        .confirmed(confirmed).assigned(assigned).onTheWay(onTheWay)
                        .delivered(delivered).collected(collected).unassigned(unassigned)
                        .build())
                .movers(movers)
                .expiring(expiring)
                .trend(trend)
                .alerts(alerts(stock, unassigned, expiring))
                .coverBuckets(coverBuckets(orgId, branch))
                .expiryRunway(expiryRunway(orgId, branch, canSeeFinancials()))
                .pipeline(pipeline(confirmed, assigned, onTheWay, delivered, collected))
                .build();
    }

    /**
     * How long the catalogue will last, in five bands.
     *
     * <p>Ordered worst to best so the bar chart reads left to right as the
     * problem shrinking, and toned so the eye lands on the first two bars
     * without having to read the axis.
     */
    private List<Slice> coverBuckets(Long orgId, Long branch) {
        Object[] r = first(repo.stockCoverBuckets(orgId, branch));
        if (r == null) return List.of();
        return List.of(
                slice("Out of stock",    lng(r, 0), "CRITICAL"),
                slice("Under a week",    lng(r, 1), "CRITICAL"),
                slice("1 to 4 weeks",    lng(r, 2), "WARNING"),
                slice("1 to 3 months",   lng(r, 3), "OK"),
                slice("Over 3 months",   lng(r, 4), "OK"),
                slice("Not moving",      lng(r, 5), "WARNING"));
    }

    /**
     * Stock by remaining shelf life.
     *
     * <p>The pieces are shown to everyone, because deciding what to sell first
     * is a shop-floor job. The value is attached only for a user entitled to
     * money, so the same chart serves both dashboards without a second query.
     */
    private List<Slice> expiryRunway(Long orgId, Long branch, boolean withValue) {
        Map<Long, Object[]> byBucket = new LinkedHashMap<>();
        for (Object[] r : repo.expiryRunway(orgId, branch)) byBucket.put(num(r[0]), r);

        List<Slice> out = new ArrayList<>();
        long[] buckets = { 0, 30, 60, 90, 180, 999 };
        String[] labels = { "Already expired", "Within 30 days", "31 to 60 days",
                            "61 to 90 days", "91 to 180 days", "Over 6 months" };
        String[] tones = { "CRITICAL", "CRITICAL", "WARNING", "WARNING", "OK", "OK" };
        for (int i = 0; i < buckets.length; i++) {
            Object[] r = byBucket.get(buckets[i]);
            out.add(Slice.builder()
                    .label(labels[i])
                    .value(r == null ? BigDecimal.ZERO : dec(r, 1))
                    .amount(!withValue || r == null ? null : scale(dec(r, 2), 2))
                    .count(r == null ? 0 : (int) num(r[3]))
                    .tone(tones[i])
                    .build());
        }
        return out;
    }

    /** The order pipeline, in the order work actually moves through it. */
    private List<Slice> pipeline(long confirmed, long assigned, long onTheWay,
                                 long delivered, long collected) {
        return List.of(
                slice("Confirmed",   confirmed, confirmed > 0 ? "WARNING" : "OK"),
                slice("Assigned",    assigned,  "OK"),
                slice("On the way",  onTheWay,  "OK"),
                slice("Delivered",   delivered, "OK"),
                slice("Collected",   collected, "OK"));
    }

    private static Slice slice(String label, long value, String tone) {
        return Slice.builder().label(label).value(BigDecimal.valueOf(value))
                .count((int) value).tone(tone).build();
    }

    /** The handful of things worth doing something about, worst first. */
    private List<Alert> alerts(StockHealth s, long unassigned, List<Expiring> expiring) {
        List<Alert> out = new ArrayList<>();

        if (s.getAlreadyExpired() > 0) {
            out.add(Alert.builder().key("EXPIRED").severity("CRITICAL")
                    .title(s.getAlreadyExpired() + " product"
                           + (s.getAlreadyExpired() == 1 ? " has" : "s have") + " expired stock on the shelf")
                    .detail("It cannot be sold and should not be counted as cover. Pull it and write it off.")
                    .count((int) s.getAlreadyExpired()).action("inventory").build());
        }
        if (s.getOutOfStock() > 0) {
            out.add(Alert.builder().key("OUT_OF_STOCK").severity("CRITICAL")
                    .title(s.getOutOfStock() + " product" + (s.getOutOfStock() == 1 ? "" : "s") + " out of stock")
                    .detail("Nothing to sell and nothing reserved. The Copilot can work out what to order.")
                    .count((int) s.getOutOfStock()).action("ai").build());
        }
        if (s.getLowStock() > 0) {
            out.add(Alert.builder().key("LOW_STOCK").severity("WARNING")
                    .title(s.getLowStock() + " product" + (s.getLowStock() == 1 ? " is" : "s are") + " at or below reorder level")
                    .detail("Still sellable, but not for long at the current rate.")
                    .count((int) s.getLowStock()).action("ai").build());
        }
        if (!expiring.isEmpty()) {
            Expiring soonest = expiring.get(0);
            out.add(Alert.builder().key("EXPIRY").severity(soonest.getDaysLeft() < 30 ? "WARNING" : "INFO")
                    .title(s.getExpiring90() + " product" + (s.getExpiring90() == 1 ? "" : "s")
                           + " dated within " + EXPIRY_HORIZON + " days")
                    .detail("Soonest is " + soonest.getProductName() + " batch " + soonest.getBatchNo()
                            + ", " + soonest.getDaysLeft() + " days left. Sell it first or move it.")
                    .count((int) s.getExpiring90()).action("inventory").build());
        }
        if (s.getDeadStock() > 0) {
            out.add(Alert.builder().key("DEAD_STOCK").severity("WARNING")
                    .title(s.getDeadStock() + " product" + (s.getDeadStock() == 1 ? " has" : "s have")
                           + " not sold in " + DEAD_STOCK_DAYS + " days")
                    .detail("Occupying shelf space and going out of date. Worth clearing, and worth not reordering.")
                    .count((int) s.getDeadStock()).action("ai").build());
        }
        if (unassigned > 0) {
            out.add(Alert.builder().key("UNASSIGNED").severity("WARNING")
                    .title(unassigned + " confirmed order" + (unassigned == 1 ? " has" : "s have") + " no delivery man")
                    .detail("Confirmed to the customer but not on anybody's round yet.")
                    .count((int) unassigned).action("delivery").build());
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       FINANCE
       ══════════════════════════════════════════════════════════════════════ */

    private Finance finance(Long orgId, Long branch, int days) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate prevStart = monthStart.minusMonths(1);
        LocalDate prevEnd = monthStart.minusDays(1);

        Period pToday = period(orgId, branch, today, today);
        Period pMonth = period(orgId, branch, monthStart, today);
        Period pPrev  = period(orgId, branch, prevStart, prevEnd);

        Object[] pt = first(repo.purchaseSpend(orgId, branch, today, today));
        Object[] pm = first(repo.purchaseSpend(orgId, branch, monthStart, today));
        Object[] sb = first(repo.supplierBenefit(orgId, branch, monthStart, today));
        Object[] rc = first(repo.receivable(orgId, branch));
        Object[] sv = first(repo.stockValue(orgId, branch));

        // Supplier outstanding comes from the shared account view, so the
        // dashboard and the supplier ledger cannot disagree about what is owed.
        BigDecimal payable = BigDecimal.ZERO, payableOverdue = BigDecimal.ZERO;
        long payableCount = 0;
        try {
            // Scoped to the branch, like every other figure on this dashboard.
            // Without it the supplier total silently included the pharmacies next
            // door, so two branches in one organisation reported the same debt.
            for (Object[] r : supplierRepo.supplierAccounts(orgId, branch, "", true, false)) {
                BigDecimal out = dec(r, 12);
                if (out.signum() > 0) { payable = payable.add(out); payableCount++; }
            }
        } catch (Exception e) {
            log.warn("Supplier outstanding unavailable for the dashboard: {}", e.getMessage());
        }

        List<DayPoint> trend = new ArrayList<>();
        for (Object[] r : repo.dailyProfit(orgId, branch, days)) {
            BigDecimal rev = dec(r, 1), cost = dec(r, 2);
            trend.add(DayPoint.builder()
                    .day(date(r[0]))
                    .revenue(scale(rev, 2))
                    .margin(scale(rev.subtract(cost), 2))
                    .build());
        }

        List<TopCustomer> customers = new ArrayList<>();
        for (Object[] r : head(repo.topCustomers(orgId, branch, days), 6)) {
            customers.add(TopCustomer.builder()
                    .name(str(r[0])).type(str(r[1]))
                    .revenue(dec(r, 2)).due(dec(r, 3)).invoices(num(r[4]))
                    .build());
        }

        List<TopProduct> products = new ArrayList<>();
        for (Object[] r : head(repo.topProductsByRevenue(orgId, branch, days), 15)) {
            BigDecimal rev = dec(r, 1), cost = dec(r, 2);
            BigDecimal margin = rev.subtract(cost);
            products.add(TopProduct.builder()
                    .name(str(r[0])).revenue(scale(rev, 2)).margin(scale(margin, 2))
                    .marginPct(pct(margin, rev)).pcs(dec(r, 3))
                    .build());
        }

        return Finance.builder()
                .today(pToday).month(pMonth).previousMonth(pPrev)
                .revenueTrendPct(pPrev.getRevenue().signum() > 0
                        ? scale(pMonth.getRevenue().subtract(pPrev.getRevenue())
                                .multiply(BigDecimal.valueOf(100))
                                .divide(pPrev.getRevenue(), 4, RoundingMode.HALF_UP), 1)
                        : null)
                .purchasesToday(scale(dec(pt, 0), 2))
                .purchasesMonth(scale(dec(pm, 0), 2))
                .supplierBenefit(benefit(sb, dec(pm, 0)))
                .receivable(Owed.builder()
                        .total(scale(dec(rc, 0), 2)).overdue(scale(dec(rc, 1), 2))
                        .count(num(rc == null ? null : rc[2])).build())
                .payable(Owed.builder()
                        .total(scale(payable, 2)).overdue(scale(payableOverdue, 2))
                        .count(payableCount).build())
                .stockValue(scale(dec(sv, 0), 2))
                .expiringValue(scale(dec(sv, 1), 2))
                .expiredValue(scale(dec(sv, 2), 2))
                .trend(trend).topCustomers(customers).topProducts(products)
                .monthly(monthly(orgId, branch))
                .receivableAgeing(receivableAgeing(orgId, branch))
                .paymentMix(paymentMix(orgId, branch, days))
                .revenueByMaker(revenueByMaker(orgId, branch, days))
                .build();
    }

    /**
     * [V67-69] The month's supplier benefit, against the month's purchases.
     *
     * <p>Gross purchases, not AMOUNT_TOBE_PAID plus nothing: the spend figure the
     * dashboard already carries has had the cashback taken off it, so dividing
     * by it would divide by a number the cashback itself shrank and report a
     * larger percentage than the supplier actually gave.
     */
    private Benefit benefit(Object[] r, BigDecimal purchasesMonth) {
        BigDecimal cashback  = scale(dec(r, 0), 2);
        BigDecimal giftValue = scale(dec(r, 1), 2);
        BigDecimal total     = cashback.add(giftValue);
        BigDecimal gross     = purchasesMonth.add(cashback);
        return Benefit.builder()
                .cashback(cashback)
                .giftValue(giftValue)
                .bonusPcs(scale(dec(r, 2), 2))
                .total(total)
                .pctOfPurchases(gross.signum() > 0 ? pct(total, gross) : null)
                .build();
    }

    /** Revenue against cost by month, oldest first so the bars read forward. */
    private List<MonthPoint> monthly(Long orgId, Long branch) {
        List<MonthPoint> out = new ArrayList<>();
        for (Object[] r : repo.monthlyRevenue(orgId, branch)) {
            BigDecimal rev = dec(r, 1), cost = dec(r, 2);
            BigDecimal margin = rev.subtract(cost);
            out.add(MonthPoint.builder()
                    .month(str(r[0]))
                    .revenue(scale(rev, 2)).cost(scale(cost, 2)).margin(scale(margin, 2))
                    .marginPct(pct(margin, rev))
                    .invoices(num(r[3]))
                    .build());
        }
        // The query takes the most recent twelve; the chart reads left to right.
        Collections.reverse(out);
        return out;
    }

    private List<Slice> receivableAgeing(Long orgId, Long branch) {
        Object[] r = first(repo.receivableAgeing(orgId, branch));
        if (r == null) return List.of();
        String[] labels = { "0 to 30 days", "31 to 60 days", "61 to 90 days", "Over 90 days" };
        String[] tones  = { "OK", "WARNING", "WARNING", "CRITICAL" };
        List<Slice> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BigDecimal amt = scale(dec(r, i), 2);
            out.add(Slice.builder().label(labels[i]).value(amt).amount(amt).tone(tones[i]).build());
        }
        return out;
    }

    private List<Slice> paymentMix(Long orgId, Long branch, int days) {
        List<Slice> out = new ArrayList<>();
        for (Object[] r : repo.paymentMix(orgId, branch, days)) {
            BigDecimal amt = scale(dec(r, 1), 2);
            out.add(Slice.builder().label(str(r[0])).value(amt).amount(amt)
                    .count((int) num(r[2])).tone("OK").build());
        }
        return out;
    }

    private List<Slice> revenueByMaker(Long orgId, Long branch, int days) {
        List<Slice> out = new ArrayList<>();
        for (Object[] r : repo.revenueByMaker(orgId, branch, days)) {
            BigDecimal amt = scale(dec(r, 1), 2);
            out.add(Slice.builder().label(str(r[0])).value(amt).amount(amt)
                    .count((int) num(r[2])).tone("OK").build());
        }
        return out;
    }

    private Period period(Long orgId, Long branch, LocalDate from, LocalDate to) {
        Object[] r = first(repo.revenueAndCost(orgId, branch, from, to));
        BigDecimal rev = dec(r, 0), cost = dec(r, 1);
        BigDecimal margin = rev.subtract(cost);
        return Period.builder()
                .revenue(scale(rev, 2)).cost(scale(cost, 2)).margin(scale(margin, 2))
                .marginPct(pct(margin, rev))
                .invoices(num(r == null ? null : r[2])).pcs(dec(r, 3))
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /** Null rather than zero: a margin on no revenue is undefined, not 0%. */
    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null
                : scale(part.multiply(BigDecimal.valueOf(100))
                        .divide(whole, 4, RoundingMode.HALF_UP), 1);
    }

    private static Object[] first(List<Object[]> rows) { return rows.isEmpty() ? null : rows.get(0); }

    /** The first n rows — the query bounds the work, this bounds the screen. */
    private static List<Object[]> head(List<Object[]> rows, int n) {
        return rows.size() <= n ? rows : rows.subList(0, n);
    }
    private static long lng(Object[] r, int i) { return r == null ? 0 : num(r[i]); }
    private static BigDecimal dec(Object[] r, int i) { return r == null ? BigDecimal.ZERO : dec(r[i]); }
    private static BigDecimal dec(Object o, int dp) { return scale(dec(o), dp); }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO
                : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal scale(BigDecimal v, int dp) {
        return (v == null ? BigDecimal.ZERO : v).setScale(dp, RoundingMode.HALF_UP);
    }
    private static LocalDate date(Object o) {
        return o == null ? null
             : o instanceof java.sql.Date d ? d.toLocalDate()
             : o instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate()
             : o instanceof LocalDate ld ? ld
             : LocalDate.parse(o.toString().substring(0, 10));
    }
}
