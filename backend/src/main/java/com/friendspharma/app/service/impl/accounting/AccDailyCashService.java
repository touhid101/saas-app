package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Branch daily cash control.
 *
 * <pre>
 *   previous CLOSED day's counted cash  →  opening
 *   + every posted debit on the till dated today  − every posted credit  →  expected closing
 *   counted cash (by denomination)  →  physical
 *   physical − expected  →  difference (SHORT / OVER / BALANCED)
 * </pre>
 *
 * <p>Nothing here is a balance of its own. Cash in and cash out are read from
 * the ledger every time an OPEN day is looked at, and snapshotted only when
 * the day is submitted or closed. The opening is the previous closed day's
 * physical cash — the money that was actually in the drawer — never a typed
 * figure. A first day, or a correction, is a cash voucher of kind OPENING or
 * ADJUSTMENT with a reason and an approval, which the ledger then shows.
 */
@Service
@RequiredArgsConstructor
public class AccDailyCashService {

    public static final String OPEN = "OPEN", SUBMITTED = "SUBMITTED", CLOSED = "CLOSED", REOPENED = "REOPENED", LOCKED = "LOCKED";

    private final AccScope                     scope;
    private final AccBusinessDayRepository     dayRepo;
    private final AccCashCountRepository       countRepo;
    private final AccBusinessDayAuditRepository auditRepo;
    private final AccDenominationRepository    denomRepo;
    private final AccChartService              chart;
    private final AccLedgerService             ledger;
    private final AccSettings                  settings;
    private final AccountingEventService       engine;
    private final AllBranchRepository          branchRepo;

    /* ── read ───────────────────────────────────────────────────────────── */

    /** The day sheet for a branch and date, computed from the ledger; a virtual NOT_STARTED sheet when none exists. */
    @Transactional(readOnly = true)
    public Map<String, Object> sheet(Long branchId, LocalDate date) {
        scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.VIEW, "see the daily cash sheet");
        Long org = scope.orgId();
        Long branch = scope.branchId(branchId);
        LocalDate d = date != null ? date : LocalDate.now();
        Optional<AccBusinessDay> existing = dayRepo.findByPidBranchAndBusinessDate(branch, d);
        AccAccount till = chart.byRole(org, "CASH_REGISTER");
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<AccBusinessDay> prev = previousClosed(branch, d);
        BigDecimal opening = existing.map(AccBusinessDay::getOpeningCash).orElseGet(() -> prev.map(AccBusinessDay::getPhysicalCash).orElse(null));
        BigDecimal[] mv = ledger.dayMovement(branch, till.getPidAccount(), d);
        BigDecimal ledgerOpening = ledger.accountBalance(branch, till.getPidAccount(), d.minusDays(1), "D");
        BigDecimal expected = (opening != null ? opening : ledgerOpening).add(mv[0]).subtract(mv[1]);
        AccBusinessDay day = existing.orElse(null);
        boolean frozen = day != null && Set.of(SUBMITTED, CLOSED, LOCKED).contains(day.getDayStatus());
        out.put("branchId", branch);
        out.put("branchName", branchRepo.findById(branch).map(AllBranch::getBranchName).orElse(null));
        out.put("businessDate", d);
        out.put("status", day == null ? "NOT_STARTED" : day.getDayStatus());
        out.put("day", day);
        out.put("previousClosing", prev.map(AccBusinessDay::getPhysicalCash).orElse(null));
        out.put("previousDate", prev.map(AccBusinessDay::getBusinessDate).orElse(null));
        out.put("ledgerOpening", ledgerOpening);
        out.put("openingCash", opening != null ? opening : ledgerOpening);
        out.put("openingSource", opening != null ? (existing.isPresent() ? "DAY" : "PREVIOUS_CLOSING") : "LEDGER");
        out.put("cashIn", frozen ? day.getCashIn() : mv[0]);
        out.put("cashOut", frozen ? day.getCashOut() : mv[1]);
        out.put("expectedClosing", frozen ? day.getExpectedClosing() : expected);
        out.put("physicalCash", day == null ? null : day.getPhysicalCash());
        out.put("difference", day == null ? null : day.getDifference());
        out.put("differenceKind", day == null ? null : day.getDifferenceKind());
        out.put("tolerance", settings.decimal(org, AccSettings.CASH_TOLERANCE));
        out.put("requiresReview", settings.is(org, AccSettings.CLOSING_REQUIRES_REVIEW));
        out.put("postsVariance", settings.is(org, AccSettings.POST_CASH_VARIANCE));
        out.put("tillAccountId", till.getPidAccount());
        out.put("movements", ledger.cashBySource(org, branch, till.getPidAccount(), d, d));
        out.put("counts", day == null ? List.of() : countRepo.findByPidBusinessDayOrderByDenomValueDesc(day.getPidBusinessDay()));
        out.put("denominations", denomRepo.findByCurrencyAndIsActiveOrderBySortOrderAsc(settings.get(org, AccSettings.CURRENCY), 1));
        out.put("audit", day == null ? List.of() : auditRepo.findByPidBusinessDayOrderByActedAtAsc(day.getPidBusinessDay()));
        out.put("unclosedEarlier", dayRepo.unclosedBefore(org, d).stream().filter(x -> x.getPidBranch().equals(branch)).map(AccBusinessDay::getBusinessDate).toList());
        out.put("canOpen", day == null && scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.CREATE));
        out.put("canCount", day != null && Set.of(OPEN, REOPENED).contains(day.getDayStatus()) && scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.EDIT));
        out.put("canClose", day != null && Set.of(OPEN, REOPENED, SUBMITTED).contains(day.getDayStatus()) && scope.has(AccScope.MOD_DAILY_CASH, SUBMITTED.equals(day.getDayStatus()) ? PermissionGuard.Right.APPROVE : PermissionGuard.Right.EDIT));
        out.put("canReopen", day != null && CLOSED.equals(day.getDayStatus()) && scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.APPROVE));
        return out;
    }

    /** The branch cash ledger: one row per day sheet in a window, drillable. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> book(Long branchId, LocalDate from, LocalDate to) {
        scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.VIEW, "see the cash book");
        Long branch = scope.branchId(branchId);
        Long org = scope.orgId();
        AccAccount till = chart.byRole(org, "CASH_REGISTER");
        LocalDate f = from != null ? from : LocalDate.now().minusDays(30), t = to != null ? to : LocalDate.now();
        Map<LocalDate, AccBusinessDay> days = new HashMap<>();
        dayRepo.between(branch, f, t).forEach(x -> days.put(x.getBusinessDate(), x));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : ledger.dailyCashBook(branch, till.getPidAccount(), f, t)) {
            LocalDate d = (LocalDate) r.get("date");
            AccBusinessDay day = days.remove(d);
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("status", day == null ? "NOT_STARTED" : day.getDayStatus());
            m.put("openingCash", day == null ? r.get("opening") : day.getOpeningCash());
            m.put("expectedClosing", day == null ? r.get("closing") : (day.getExpectedClosing() != null ? day.getExpectedClosing() : r.get("closing")));
            m.put("physicalCash", day == null ? null : day.getPhysicalCash());
            m.put("difference", day == null ? null : day.getDifference());
            m.put("differenceKind", day == null ? null : day.getDifferenceKind());
            m.put("closedBy", day == null ? null : day.getClosedBy());
            m.put("closedAt", day == null ? null : day.getClosedAt());
            m.put("dayId", day == null ? null : day.getPidBusinessDay());
            out.add(m);
        }
        // Days with a sheet but no ledger movement still belong in the book.
        for (AccBusinessDay day : days.values()) {
            out.add(AccLedgerService.row("date", day.getBusinessDate(), "opening", day.getOpeningCash(), "cashIn", day.getCashIn(), "cashOut", day.getCashOut(),
                    "closing", day.getExpectedClosing(), "count", 0, "status", day.getDayStatus(), "openingCash", day.getOpeningCash(), "expectedClosing", day.getExpectedClosing(),
                    "physicalCash", day.getPhysicalCash(), "difference", day.getDifference(), "differenceKind", day.getDifferenceKind(),
                    "closedBy", day.getClosedBy(), "closedAt", day.getClosedAt(), "dayId", day.getPidBusinessDay()));
        }
        out.sort(Comparator.comparing(m -> ((LocalDate) m.get("date"))));
        Collections.reverse(out);
        return out;
    }

    /* ── open ───────────────────────────────────────────────────────────── */

    @Transactional
    public Map<String, Object> open(Long branchId, LocalDate date) {
        scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.CREATE, "open the business day");
        Long org = scope.orgId();
        Long branch = scope.branchId(branchId);
        LocalDate d = date != null ? date : LocalDate.now();
        if (d.isAfter(LocalDate.now())) throw bad("A business day cannot be opened in the future.");
        if (dayRepo.findByPidBranchAndBusinessDate(branch, d).isPresent()) throw new BusinessException("The day " + d + " is already open for this branch.", HttpStatus.CONFLICT, "ACC_DAY_EXISTS");
        List<AccBusinessDay> open = dayRepo.openDays(branch);
        if (!open.isEmpty()) {
            throw new BusinessException("Close " + open.get(0).getBusinessDate() + " before opening " + d + " — one business day is open at a time.",
                    HttpStatus.CONFLICT, "ACC_DAY_STILL_OPEN");
        }
        chart.ensureProvisioned(org);
        AccAccount till = chart.byRole(org, "CASH_REGISTER");
        Optional<AccBusinessDay> prev = previousClosed(branch, d);
        // The opening is the previous closed day's counted cash. Before any day
        // has been closed it is the ledger's till balance, which a first day
        // establishes with an OPENING cash voucher.
        BigDecimal opening = prev.map(AccBusinessDay::getPhysicalCash)
                .orElseGet(() -> ledger.accountBalance(branch, till.getPidAccount(), d.minusDays(1), "D"));
        AccBusinessDay day = dayRepo.save(AccBusinessDay.builder()
                .pidOrg(org).pidBranch(branch).businessDate(d).pidCashAccount(till.getPidAccount()).dayStatus(OPEN)
                .pidPreviousDay(prev.map(AccBusinessDay::getPidBusinessDay).orElse(null)).openingCash(opening)
                .openedBy(scope.username()).openedAt(LocalDateTime.now()).reopenCount(0).build());
        audit(day, "OPENED", "Opening cash " + opening.toPlainString() + (prev.isPresent() ? " from " + prev.get().getBusinessDate() + " closing" : " from the ledger"));
        return sheet(branch, d);
    }

    /* ── count ──────────────────────────────────────────────────────────── */

    /** Records the denomination count (replacing any earlier count) and the physical total. */
    @Transactional
    public Map<String, Object> count(Long dayId, List<Map<String, Object>> counts, BigDecimal physicalTotal) {
        scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.EDIT, "count the cash");
        AccBusinessDay day = owned(dayId);
        if (!Set.of(OPEN, REOPENED).contains(day.getDayStatus())) throw new BusinessException("The day " + day.getBusinessDate() + " is " + day.getDayStatus().toLowerCase() + "; the count cannot change.", HttpStatus.CONFLICT, "ACC_DAY_FROZEN");
        countRepo.deleteByPidBusinessDay(day.getPidBusinessDay());
        countRepo.flush();   // the delete must reach the database before the re-inserts, or uq_acc_cash_count refuses a recount
        BigDecimal total = BigDecimal.ZERO;
        if (counts != null) {
            for (Map<String, Object> c : counts) {
                BigDecimal value = new BigDecimal(String.valueOf(c.get("denomValue")));
                int qty = Integer.parseInt(String.valueOf(c.getOrDefault("quantity", 0)));
                if (qty < 0) throw bad("A count cannot be negative.");
                if (qty == 0) continue;
                BigDecimal amount = value.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                countRepo.save(AccCashCount.builder().pidBusinessDay(day.getPidBusinessDay()).denomValue(value).quantity(qty).amount(amount).build());
                total = total.add(amount);
            }
        }
        BigDecimal physical = (counts != null && !counts.isEmpty()) ? total : (physicalTotal != null ? physicalTotal.setScale(2, RoundingMode.HALF_UP) : null);
        if (physical == null) throw bad("Enter the cash counted, by denomination or as a total.");
        refreshFromLedger(day);
        day.setPhysicalCash(physical);
        setDifference(day);
        dayRepo.save(day);
        audit(day, "COUNTED", "Physical " + physical.toPlainString() + " vs expected " + day.getExpectedClosing().toPlainString() + " (" + day.getDifferenceKind() + " " + day.getDifference().toPlainString() + ")");
        return sheet(day.getPidBranch(), day.getBusinessDate());
    }

    /* ── close ──────────────────────────────────────────────────────────── */

    /**
     * Submits the day. With review required it waits as SUBMITTED for Approve;
     * otherwise it closes at once. The expected cash is snapshotted from the
     * ledger at this moment; a cash posting dated on the day is refused from here on.
     */
    @Transactional
    public Map<String, Object> close(Long dayId, String differenceReason, String notes) {
        AccBusinessDay day = owned(dayId);
        Long org = day.getPidOrg();
        boolean review = settings.is(org, AccSettings.CLOSING_REQUIRES_REVIEW);
        if (SUBMITTED.equals(day.getDayStatus())) {
            scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.APPROVE, "approve the closing");
        } else {
            scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.EDIT, "close the business day");
            if (!Set.of(OPEN, REOPENED).contains(day.getDayStatus())) throw new BusinessException("The day " + day.getBusinessDate() + " is already " + day.getDayStatus().toLowerCase() + ".", HttpStatus.CONFLICT, "ACC_DAY_FROZEN");
            if (day.getPhysicalCash() == null) throw bad("Count the cash before closing the day.");
            refreshFromLedger(day);
            setDifference(day);
            BigDecimal tolerance = settings.decimal(org, AccSettings.CASH_TOLERANCE);
            if (day.getDifference().abs().compareTo(tolerance) > 0 && (differenceReason == null || differenceReason.isBlank())) {
                throw bad("The count is " + day.getDifferenceKind().toLowerCase() + " by " + day.getDifference().abs().toPlainString() + ". Say why before closing.");
            }
            if (differenceReason != null && !differenceReason.isBlank()) day.setDifferenceReason(AccountingEventService.trim(differenceReason.trim(), 500));
            if (notes != null) day.setNotes(AccountingEventService.trim(notes, 1000));
            day.setSubmittedBy(scope.username()); day.setSubmittedAt(LocalDateTime.now());
            if (review && !scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.APPROVE)) {
                day.setDayStatus(SUBMITTED);
                dayRepo.save(day);
                audit(day, "SUBMITTED", "Submitted for review: expected " + day.getExpectedClosing() + ", counted " + day.getPhysicalCash());
                return sheet(day.getPidBranch(), day.getBusinessDate());
            }
        }
        if (SUBMITTED.equals(day.getDayStatus())) { day.setReviewedBy(scope.username()); day.setReviewedAt(LocalDateTime.now()); audit(day, "REVIEWED", "Closing approved"); }
        // Variance, only where policy says so: the ledger is made equal to the drawer.
        if (day.getDifference().signum() != 0 && settings.is(org, AccSettings.POST_CASH_VARIANCE)) {
            String type = day.getDifference().signum() < 0 ? "CASH_VARIANCE_SHORT" : "CASH_VARIANCE_OVER";
            FinancialEvent ev = FinancialEvent.of("DAILY_CASH", "BUSINESS_DAY", day.getPidBusinessDay(), "VARIANCE:" + (day.getReopenCount() == null ? 0 : day.getReopenCount()), type)
                    .in(org, day.getPidBranch()).on(day.getBusinessDate())
                    .ref("DAY " + day.getBusinessDate(), "Cash " + day.getDifferenceKind().toLowerCase() + " at closing: " + (day.getDifferenceReason() == null ? "" : day.getDifferenceReason()))
                    .amount("AMOUNT", day.getDifference().abs());
            ev.historical = true;   // the day is being closed: this posting belongs to it by definition
            AccJournal j = engine.post(ev);
            if (j != null) { day.setVariancePosted(1); day.setPidVarianceJournal(j.getPidJournal()); audit(day, "VARIANCE_POSTED", "Variance " + day.getDifference() + " posted as " + j.getJournalNo()); }
        }
        day.setDayStatus(CLOSED); day.setClosedBy(scope.username()); day.setClosedAt(LocalDateTime.now());
        dayRepo.save(day);
        audit(day, "CLOSED", "Closed with " + day.getDifferenceKind() + " " + day.getDifference().toPlainString() + "; carries " + day.getPhysicalCash().toPlainString() + " forward");
        return sheet(day.getPidBranch(), day.getBusinessDate());
    }

    /** Reopens a closed day with a reason and Approve. Later days that took their opening from it must not exist. */
    @Transactional
    public Map<String, Object> reopen(Long dayId, String reason) {
        scope.require(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.APPROVE, "reopen a closed day");
        AccBusinessDay day = owned(dayId);
        if (!CLOSED.equals(day.getDayStatus())) throw new BusinessException("Only a closed day can be reopened.", HttpStatus.CONFLICT, "ACC_DAY_NOT_CLOSED");
        if (reason == null || reason.isBlank()) throw bad("Say why the day is being reopened.");
        List<AccBusinessDay> later = dayRepo.between(day.getPidBranch(), day.getBusinessDate().plusDays(1), LocalDate.now().plusDays(1));
        if (!later.isEmpty()) throw new BusinessException("A later day (" + later.get(later.size() - 1).getBusinessDate() + ") took its opening from this closing. Reopen the later day first.", HttpStatus.CONFLICT, "ACC_DAY_HAS_SUCCESSOR");
        if (Integer.valueOf(1).equals(day.getVariancePosted()) && day.getPidVarianceJournal() != null) {
            engine.reverseSource(day.getPidOrg(), "DAILY_CASH", "BUSINESS_DAY", day.getPidBusinessDay(), LocalDate.now(), "Day " + day.getBusinessDate() + " reopened: " + reason.trim());
            day.setVariancePosted(0); day.setPidVarianceJournal(null);
        }
        day.setDayStatus(REOPENED); day.setReopenedBy(scope.username()); day.setReopenedAt(LocalDateTime.now());
        day.setReopenReason(AccountingEventService.trim(reason.trim(), 500)); day.setReopenCount((day.getReopenCount() == null ? 0 : day.getReopenCount()) + 1);
        day.setClosedBy(null); day.setClosedAt(null); day.setSubmittedBy(null); day.setSubmittedAt(null);
        dayRepo.save(day);
        audit(day, "REOPENED", reason.trim());
        return sheet(day.getPidBranch(), day.getBusinessDate());
    }

    /** Days not closed before today, across the organisation — the control dashboard's list. */
    @Transactional(readOnly = true)
    public List<AccBusinessDay> unclosed() {
        Long branch = scope.branchFilter(null);
        return dayRepo.unclosedBefore(scope.orgId(), LocalDate.now()).stream().filter(d -> branch == null || d.getPidBranch().equals(branch)).toList();
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private Optional<AccBusinessDay> previousClosed(Long branch, LocalDate before) {
        return dayRepo.closedBefore(branch, before, PageRequest.of(0, 1)).stream().findFirst();
    }

    private void refreshFromLedger(AccBusinessDay day) {
        BigDecimal[] mv = ledger.dayMovement(day.getPidBranch(), day.getPidCashAccount(), day.getBusinessDate());
        day.setCashIn(mv[0]); day.setCashOut(mv[1]);
        day.setExpectedClosing(day.getOpeningCash().add(mv[0]).subtract(mv[1]).setScale(2, RoundingMode.HALF_UP));
    }

    private void setDifference(AccBusinessDay day) {
        if (day.getPhysicalCash() == null || day.getExpectedClosing() == null) return;
        BigDecimal diff = day.getPhysicalCash().subtract(day.getExpectedClosing()).setScale(2, RoundingMode.HALF_UP);
        day.setDifference(diff);
        day.setDifferenceKind(diff.signum() == 0 ? "BALANCED" : diff.signum() < 0 ? "SHORT" : "OVER");
    }

    private void audit(AccBusinessDay day, String action, String detail) {
        auditRepo.save(AccBusinessDayAudit.builder().pidBusinessDay(day.getPidBusinessDay()).action(action)
                .detail(AccountingEventService.trim(detail, 1000)).actedBy(scope.username()).actedAt(LocalDateTime.now()).build());
    }

    private AccBusinessDay owned(Long id) {
        AccBusinessDay d = dayRepo.findByPidBusinessDayAndPidOrg(id, scope.orgId()).orElseThrow(() -> new BusinessException("Day sheet not found.", HttpStatus.NOT_FOUND, "ACC_DAY_NOT_FOUND"));
        Long b = scope.branchFilter(null);
        if (b != null && !b.equals(d.getPidBranch())) throw new BusinessException("Day sheet not found.", HttpStatus.NOT_FOUND, "ACC_DAY_NOT_FOUND");
        return d;
    }

    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
