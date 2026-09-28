package com.friendspharma.app.service.impl;

import com.friendspharma.app.repository.PlatformConsoleRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the platform itself is doing.
 *
 * <p>Assembled in one call rather than eight, because the console draws all of it
 * at once and eight round trips from the browser is eight chances for the screen
 * to render half-finished.
 *
 * <p>The judgements are made here rather than on the screen — whether a pharmacy
 * is at risk, whether a renewal is urgent — so that the rule lives in one place
 * and a second screen showing the same list cannot disagree with the first.
 */
@Service
@RequiredArgsConstructor
public class PlatformConsoleService {

    /** Close enough to expiry to be worth a phone call. */
    private static final int RENEWAL_HORIZON_DAYS = 30;

    /** Below this many sales in a month, a paying pharmacy has stopped using it. */
    private static final int QUIET_THRESHOLD = 5;

    private final PlatformConsoleRepository repo;

    @Data @Builder
    public static class Console {
        private BigDecimal monthlyRecurringRevenue;
        private BigDecimal outstandingTotal;
        private int        outstandingCount;
        private long       pendingApplications;
        private Map<String, Long> tenantsByStatus;
        private int        atRiskCount;

        private List<Map<String, Object>> collections;   // month, collected, payments
        private List<Map<String, Object>> signups;       // month, count
        private List<Map<String, Object>> outstanding;   // invoice rows, oldest first
        private List<Map<String, Object>> expiringSoon;  // renewals due
        private List<Map<String, Object>> activity;      // who is actually trading
        private List<Map<String, Object>> audit;
        private LocalDate asOf;
    }

    @Transactional(readOnly = true)
    public Console load() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("ACTIVE", "PENDING", "SUSPENDED", "EXPIRED")) byStatus.put(s, 0L);
        for (Object[] r : repo.tenantsByStatus()) {
            byStatus.merge(str(r[0]), num(r[1]).longValue(), Long::sum);
        }

        List<Map<String, Object>> outstanding = new ArrayList<>();
        BigDecimal owed = BigDecimal.ZERO;
        for (Object[] r : repo.outstandingInvoices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", num(r[0]).longValue());
            m.put("invoiceNo", str(r[1]));
            m.put("branchId", num(r[2]).longValue());
            m.put("branchName", str(r[3]));
            m.put("amount", dec(r[4]));
            m.put("dueOn", str(r[5]));
            m.put("daysOverdue", num(r[6]).intValue());
            outstanding.add(m);
            owed = owed.add(dec(r[4]));
        }

        List<Map<String, Object>> expiring = new ArrayList<>();
        for (Object[] r : repo.expiringWithin(RENEWAL_HORIZON_DAYS)) {
            int daysLeft = num(r[4]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branchId", num(r[0]).longValue());
            m.put("branchName", str(r[1]));
            m.put("planName", str(r[2]));
            m.put("expiresOn", str(r[3]));
            m.put("daysLeft", daysLeft);
            m.put("graceDays", num(r[5]).intValue());
            // Decided here, not on the screen: two screens showing this list must
            // not disagree about which rows are urgent.
            m.put("urgent", daysLeft <= 7);
            expiring.add(m);
        }

        List<Map<String, Object>> activity = new ArrayList<>();
        int atRisk = 0;
        for (Object[] r : repo.tenantActivity()) {
            int recent = num(r[2]).intValue();
            int previous = num(r[3]).intValue();
            boolean quiet = recent < QUIET_THRESHOLD;
            // Falling by more than half is the shape of a customer on the way out,
            // and it is visible a month before the cancellation is.
            boolean falling = previous >= QUIET_THRESHOLD && recent * 2 < previous;
            if (quiet || falling) atRisk++;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branchId", num(r[0]).longValue());
            m.put("branchName", str(r[1]));
            m.put("sales30", recent);
            m.put("salesPrev30", previous);
            m.put("lastSale", str(r[4]));
            m.put("users", num(r[5]).intValue());
            m.put("quiet", quiet);
            m.put("falling", falling);
            activity.add(m);
        }

        return Console.builder()
                .monthlyRecurringRevenue(repo.monthlyRecurringRevenue())
                .outstandingTotal(owed)
                .outstandingCount(outstanding.size())
                .pendingApplications(repo.pendingApplications())
                .tenantsByStatus(byStatus)
                .atRiskCount(atRisk)
                .collections(twoColumn(repo.collectionsByMonth(), "collected", "payments"))
                .signups(oneColumn(repo.signupsByMonth(), "count"))
                .outstanding(outstanding)
                .expiringSoon(expiring)
                .activity(activity)
                .audit(auditRows())
                .asOf(LocalDate.now())
                .build();
    }

    private List<Map<String, Object>> auditRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : repo.recentAudit()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branchId", r[0] == null ? null : num(r[0]).longValue());
            m.put("branchName", str(r[1]));
            m.put("eventType", str(r[2]));
            m.put("detail", str(r[3]));
            m.put("oldValue", str(r[4]));
            m.put("newValue", str(r[5]));
            m.put("actedBy", str(r[6]));
            m.put("actedAt", r[7] == null ? null
                    : ((java.sql.Timestamp) r[7]).toLocalDateTime().toString());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> twoColumn(List<Object[]> rows, String a, String b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", str(r[0]));
            m.put(a, dec(r[1]));
            m.put(b, num(r[2]).intValue());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> oneColumn(List<Object[]> rows, String a) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", str(r[0]));
            m.put(a, num(r[1]).intValue());
            out.add(m);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Number num(Object o) { return o == null ? 0 : (Number) o; }
    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO
                : o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }
}
