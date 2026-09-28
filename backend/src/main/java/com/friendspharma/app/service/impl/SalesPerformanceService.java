package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.SalesPerformanceResponse;
import com.friendspharma.app.dto.response.SalesPerformanceResponse.Row;
import com.friendspharma.app.dto.response.SalesPerformanceResponse.TrendPoint;
import com.friendspharma.app.dto.response.SalesPerformanceResponse.Totals;
import com.friendspharma.app.entity.PosSalesTarget;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.SalesPerformanceRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales performance, per person, per month.
 *
 * <p><b>What this replaces.</b> The screen had no backend. It built eight
 * salespeople, their targets, their actuals, their commission and six months of
 * trend from a seeded random number generator in {@code mockData.js}. Every
 * figure on it was invented, and the same figures appeared for every pharmacy.
 *
 * <p><b>Attribution.</b> {@code INSERTED_BY} — the username that raised the
 * invoice. {@code POS_TRANSACTION_MST.PID_EMPLOYEE} is the column that looks
 * right and is not: it is NULL on all 140,390 sales in this database and
 * nothing has ever written it. A report built on it would be permanently empty.
 *
 * <p><b>Targets are stored, not guessed.</b> They live in POS_SALES_TARGET and
 * somebody has to set them. A person without one is reported on actuals alone,
 * with the target columns blank — never with a target of zero, which every
 * seller beats, and never with a generated one.
 *
 * <p>Branch-scoped throughout, and gated on this module's own rights: View to
 * read, Edit to set a target.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesPerformanceService {

    /** Sales Performance. Its View and Edit rights are the gate. */
    private static final String MODULE = "MOD07";

    /** Months of history behind the table. Long enough to show a direction. */
    private static final int TREND_MONTHS = 6;

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy");

    private final SalesPerformanceRepository repo;
    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public SalesPerformanceResponse forMonth(LocalDate requestedMonth) {
        Long branchId = branchScope.resolve(null);
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) throw new ValidationException("Your account has no organisation assigned.");

        LocalDate month = firstOfMonth(requestedMonth == null ? LocalDate.now() : requestedMonth);
        LocalDate from  = month;
        LocalDate to    = month.plusMonths(1).minusDays(1);

        // Targets first: the rows below are matched to them by user id.
        Map<Long, PosSalesTarget> targets = new HashMap<>();
        for (PosSalesTarget t : repo.targetsFor(branchId, month)) {
            targets.put(t.getPidUser(), t);
        }

        List<Row> rows = new ArrayList<>();
        for (Object[] r : repo.actualsByPerson(orgId, branchId, from, to)) {
            rows.add(toRow(r, targets));
        }

        return SalesPerformanceResponse.builder()
                .month(month)
                .canSetTargets(permissions.has(MODULE, PermissionGuard.Right.EDIT))
                .rows(rows)
                .totals(totalsOf(rows))
                .trend(trend(orgId, branchId, month))
                .build();
    }

    private Row toRow(Object[] r, Map<Long, PosSalesTarget> targets) {
        String person      = str(r[0]);
        Long   userId      = r[1] == null ? null : ((Number) r[1]).longValue();
        String displayName = str(r[2]);
        String roleName    = str(r[3]);
        long   invoices    = num(r[4]);
        long   customers   = num(r[5]);
        BigDecimal billed    = dec(r[6]);
        BigDecimal collected = dec(r[7]);
        BigDecimal discount  = dec(r[8]);
        LocalDate lastSale   = r[9] == null ? null : ((java.sql.Date) r[9]).toLocalDate();

        PosSalesTarget target = userId == null ? null : targets.get(userId);
        BigDecimal targetAmount = target == null ? null : target.getTargetAmount();
        BigDecimal commissionPct = target == null ? null : target.getCommissionPct();

        // Achievement only where there is something to achieve. A target of zero
        // would otherwise divide into an infinite percentage.
        BigDecimal achievement = (targetAmount == null || targetAmount.signum() <= 0)
                ? null
                : billed.multiply(BigDecimal.valueOf(100))
                        .divide(targetAmount, 1, RoundingMode.HALF_UP);

        // Commission on what was billed, not collected. That is a policy choice
        // and the only one the data can support: nothing records which invoice a
        // later payment settled, so commission on collections is not derivable.
        BigDecimal commission = commissionPct == null ? null
                : billed.multiply(commissionPct)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        return Row.builder()
                .person(person)
                .userId(userId)
                .displayName(displayName)
                .roleName(roleName)
                .invoices(invoices)
                .customers(customers)
                .billed(billed)
                .collected(collected)
                .outstanding(billed.subtract(collected))
                .discount(discount)
                .averageInvoice(invoices == 0 ? BigDecimal.ZERO
                        : billed.divide(BigDecimal.valueOf(invoices), 2, RoundingMode.HALF_UP))
                .lastSale(lastSale)
                .target(targetAmount)
                .achievementPct(achievement)
                .commissionPct(commissionPct)
                .commission(commission)
                // Only a real login can carry a target. Invoices raised by a seed
                // script or an import have nobody to hold to one.
                .targetable(userId != null)
                .build();
    }

    private Totals totalsOf(List<Row> rows) {
        long invoices = 0, withTarget = 0;
        BigDecimal billed = BigDecimal.ZERO, collected = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO, target = BigDecimal.ZERO, commission = BigDecimal.ZERO;

        for (Row r : rows) {
            invoices  += r.getInvoices();
            billed     = billed.add(r.getBilled());
            collected  = collected.add(r.getCollected());
            discount   = discount.add(r.getDiscount());
            if (r.getTarget() != null) {
                withTarget++;
                target = target.add(r.getTarget());
            }
            if (r.getCommission() != null) commission = commission.add(r.getCommission());
        }

        // Achievement across the team compares like with like: only the billing
        // of people who have a target counts against the sum of those targets.
        // Including everyone's sales would flatter the team by the takings of
        // whoever nobody set a target for.
        BigDecimal billedWithTarget = rows.stream()
                .filter(r -> r.getTarget() != null)
                .map(Row::getBilled)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal achievement = target.signum() <= 0 ? null
                : billedWithTarget.multiply(BigDecimal.valueOf(100))
                                  .divide(target, 1, RoundingMode.HALF_UP);

        return Totals.builder()
                .people(rows.size())
                .invoices(invoices)
                .billed(billed)
                .collected(collected)
                .outstanding(billed.subtract(collected))
                .discount(discount)
                .target(target.signum() == 0 ? null : target)
                .achievementPct(achievement)
                .commission(commission)
                .withTarget(withTarget)
                .build();
    }

    /**
     * Six months to the month being viewed.
     *
     * <p>Months with no trade are filled in as zero rather than omitted. A gap
     * in a trend reads as missing data; a zero reads as a quiet month, and the
     * second is what actually happened.
     */
    private List<TrendPoint> trend(Long orgId, Long branchId, LocalDate month) {
        LocalDate first = month.minusMonths(TREND_MONTHS - 1L);
        LocalDate last  = month.plusMonths(1).minusDays(1);

        Map<LocalDate, Object[]> byMonth = new HashMap<>();
        for (Object[] r : repo.monthlyTotals(orgId, branchId, first, last)) {
            byMonth.put(((java.sql.Date) r[0]).toLocalDate(), r);
        }

        // One query for the targets of every month in the window, summed per month.
        Map<LocalDate, BigDecimal> targetByMonth = new LinkedHashMap<>();
        for (int i = 0; i < TREND_MONTHS; i++) {
            LocalDate m = first.plusMonths(i);
            BigDecimal sum = repo.targetsFor(branchId, m).stream()
                    .map(PosSalesTarget::getTargetAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            targetByMonth.put(m, sum.signum() == 0 ? null : sum);
        }

        List<TrendPoint> out = new ArrayList<>();
        for (int i = 0; i < TREND_MONTHS; i++) {
            LocalDate m = first.plusMonths(i);
            Object[] row = byMonth.get(m);
            out.add(TrendPoint.builder()
                    .month(m)
                    .label(m.format(MONTH_LABEL))
                    .invoices(row == null ? 0 : num(row[1]))
                    .billed(row == null ? BigDecimal.ZERO : dec(row[2]))
                    .target(targetByMonth.get(m))
                    .build());
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Sets or clears one person's target for a month.
     *
     * <p>A null or zero amount removes the target rather than storing nothing as
     * a goal, so "not set" stays distinguishable from "asked for nothing".
     */
    @Transactional
    public void setTarget(Long pidUser, LocalDate requestedMonth,
                          BigDecimal amount, BigDecimal commissionPct) {

        if (!permissions.has(MODULE, PermissionGuard.Right.EDIT)) {
            throw new BusinessException(
                    "You do not have permission to set sales targets. Ask for the Edit right on "
                    + "Sales Performance in RBAC.",
                    HttpStatus.FORBIDDEN, "TARGET_EDIT_NOT_PERMITTED");
        }
        if (pidUser == null) throw new ValidationException("Choose whose target this is.");

        Long branchId = branchScope.resolve(null);
        Long orgId = SecurityUtils.getCurrentOrgId();
        LocalDate month = firstOfMonth(requestedMonth == null ? LocalDate.now() : requestedMonth);

        if (commissionPct != null
                && (commissionPct.signum() < 0 || commissionPct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new ValidationException("Commission must be between 0 and 100 percent.");
        }
        if (amount != null && amount.signum() < 0) {
            throw new ValidationException("A target cannot be negative.");
        }

        var existing = repo.findTarget(branchId, pidUser, month);

        if (amount == null || amount.signum() == 0) {
            existing.ifPresent(repo::delete);
            return;
        }

        String who = SecurityUtils.getCurrentUsername();
        PosSalesTarget target = existing.orElseGet(() -> PosSalesTarget.builder()
                .pidOrg(orgId)
                .pidBranch(branchId)
                .pidUser(pidUser)
                .periodMonth(month)
                .status("Y")
                .insertedBy(who)
                .insertedDate(LocalDateTime.now())
                .build());

        target.setTargetAmount(amount);
        target.setCommissionPct(commissionPct == null ? BigDecimal.ZERO : commissionPct);
        target.setStatus("Y");
        if (target.getPidTarget() != null) {
            target.setUpdatedBy(who);
            target.setUpdatedDate(LocalDateTime.now());
        }
        repo.save(target);
    }

    /* ── plumbing ────────────────────────────────────────────────────────── */

    private static LocalDate firstOfMonth(LocalDate d) { return d.withDayOfMonth(1); }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static long num(Object o)   { return o == null ? 0L : ((Number) o).longValue(); }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
