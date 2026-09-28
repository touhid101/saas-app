package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.DocumentNumberService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
 * Manual journals and the journal register.
 *
 * <p>A manual journal is the accountant's own entry — an accrual, a
 * correction, an opening balance. It is drafted, then posted; posting runs
 * the same checks as an automatic journal (balance, period, closed day, leaf
 * and active accounts) and records an event so the monitor lists it beside
 * the automatic ones. Once posted it is as immutable as any other; correction
 * is a reversal with a reason.
 */
@Service
@RequiredArgsConstructor
public class AccJournalService {

    public static final class LineIn {
        public Long accountId; public BigDecimal debit; public BigDecimal credit; public String description;
        public String partyType; public Long partyId; public String partyName; public Long costCenterId;
    }
    public static final class JournalIn {
        public LocalDate journalDate; public Long branchId; public String reference; public String description;
        public Long costCenterId; public String journalType; public List<LineIn> lines = new ArrayList<>();
    }

    private final AccScope                  scope;
    private final AccJournalRepository      journalRepo;
    private final AccJournalLineRepository  lineRepo;
    private final AccEventRepository        eventRepo;
    private final AccChartService           chart;
    private final AccPeriodService          periods;
    private final AccSettings               settings;
    private final AccountingEventService    engine;
    private final AccLedgerService          ledger;
    private final DocumentNumberService     numbers;
    private final AccLedgerVersion          ledgerVersion;
    private final AccBusinessDayRepository  dayRepo;

    /* ── register ───────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Page<AccJournal> search(Long branchId, String status, String type, LocalDate from, LocalDate to, String q, int page, int size) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.VIEW, "see journals");
        LocalDate f = from != null ? from : LocalDate.now().minusDays(30), t = to != null ? to : LocalDate.now();
        String qq = q == null || q.isBlank() ? "" : "%" + q.trim().toLowerCase() + "%";
        return journalRepo.search(scope.orgId(), scope.branchFilter(branchId), blank(status), blank(type), f, t, qq, PageRequest.of(page, Math.min(size, 200)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.VIEW, "see a journal");
        AccJournal j = owned(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journal", j);
        out.put("lines", "DRAFT".equals(j.getJournalStatus()) ? draftLines(j.getPidJournal()) : ledger.journalLines(j.getPidJournal()));
        out.put("event", j.getPidEvent() == null ? null : eventRepo.findById(j.getPidEvent()).orElse(null));
        out.put("reversal", j.getReversedBy() == null ? null : journalRepo.findById(j.getReversedBy()).map(AccJournal::getJournalNo).orElse(null));
        out.put("reversalOf", j.getReversalOf() == null ? null : journalRepo.findById(j.getReversalOf()).map(AccJournal::getJournalNo).orElse(null));
        return out;
    }

    private List<Map<String, Object>> draftLines(Long journalId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccJournalLine l : lineRepo.findByPidJournalOrderByLineNoAsc(journalId)) {
            AccAccount a = chart.owned(l.getPidOrg(), l.getPidAccount());
            out.add(AccLedgerService.row("lineNo", l.getLineNo(), "accountId", l.getPidAccount(), "code", a.getAccountCode(), "account", a.getAccountName(),
                    "debit", l.getDebit(), "credit", l.getCredit(), "description", l.getDescription(), "partyType", l.getPartyType(), "partyId", l.getPartyId(),
                    "partyName", l.getPartyName(), "branchId", l.getPidBranch(), "costCenterId", l.getPidCostCenter()));
        }
        return out;
    }

    /* ── manual journals ────────────────────────────────────────────────── */

    @Transactional
    public AccJournal createDraft(JournalIn in) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.CREATE, "create a journal");
        Long org = scope.orgId();
        Long branch = scope.branchId(in.branchId);
        chart.ensureProvisioned(org);
        validate(org, in);
        LocalDate date = in.journalDate != null ? in.journalDate : LocalDate.now();
        AccPeriod period = periods.periodFor(org, date);
        String type = in.journalType == null || in.journalType.isBlank() ? "MANUAL" : in.journalType;
        if (!Set.of("MANUAL", "ADJUSTMENT", "OPENING").contains(type)) throw bad("Journal type must be MANUAL, ADJUSTMENT or OPENING.");
        BigDecimal[] totals = totals(in);
        AccJournal j = journalRepo.save(AccJournal.builder()
                .pidOrg(org).pidBranch(branch).journalNo(numbers.nextNumber("SEQ_JOURNAL_NO", "JV")).journalDate(date)
                .pidPeriod(period.getPidPeriod()).journalType(type).sourceModule("MANUAL").sourceType("JOURNAL")
                .reference(AccountingEventService.trim(in.reference, 60)).description(AccountingEventService.trim(in.description, 300))
                .pidCostCenter(in.costCenterId).currency(settings.get(org, AccSettings.CURRENCY))
                .totalDebit(totals[0]).totalCredit(totals[1]).journalStatus("DRAFT").build());
        j.setSourceId(j.getPidJournal());
        writeLines(j, in, date);
        journalRepo.save(j);
        if (settings.is(org, AccSettings.MANUAL_JOURNAL_AUTO_POST) && scope.has(AccScope.MOD_JOURNALS, PermissionGuard.Right.APPROVE)) {
            return post(j.getPidJournal());
        }
        return j;
    }

    @Transactional
    public AccJournal updateDraft(Long id, JournalIn in) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.EDIT, "edit a journal");
        AccJournal j = owned(id);
        if (!"DRAFT".equals(j.getJournalStatus())) throw new BusinessException("Journal " + j.getJournalNo() + " is posted and cannot be edited; reverse it instead.", HttpStatus.CONFLICT, "ACC_IMMUTABLE");
        validate(j.getPidOrg(), in);
        LocalDate date = in.journalDate != null ? in.journalDate : j.getJournalDate();
        AccPeriod period = periods.periodFor(j.getPidOrg(), date);
        BigDecimal[] totals = totals(in);
        j.setJournalDate(date); j.setPidPeriod(period.getPidPeriod());
        if (in.reference != null) j.setReference(AccountingEventService.trim(in.reference, 60));
        if (in.description != null) j.setDescription(AccountingEventService.trim(in.description, 300));
        j.setPidCostCenter(in.costCenterId); j.setTotalDebit(totals[0]); j.setTotalCredit(totals[1]);
        lineRepo.deleteAll(lineRepo.findByPidJournalOrderByLineNoAsc(j.getPidJournal()));
        writeLines(j, in, date);
        return journalRepo.save(j);
    }

    @Transactional
    public void discardDraft(Long id) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.DELETE, "discard a draft journal");
        AccJournal j = owned(id);
        if (!"DRAFT".equals(j.getJournalStatus())) throw new BusinessException("Only a draft can be discarded. A posted journal is reversed.", HttpStatus.CONFLICT, "ACC_IMMUTABLE");
        lineRepo.deleteAll(lineRepo.findByPidJournalOrderByLineNoAsc(j.getPidJournal()));
        journalRepo.delete(j);
    }

    /** Posts a draft: the same rules as the engine, then immutable. */
    @Transactional
    public AccJournal post(Long id) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.APPROVE, "post a journal");
        AccJournal j = owned(id);
        if (!"DRAFT".equals(j.getJournalStatus())) throw new BusinessException("Journal " + j.getJournalNo() + " is already " + j.getJournalStatus().toLowerCase() + ".", HttpStatus.CONFLICT, "ACC_NOT_DRAFT");
        List<AccJournalLine> lines = lineRepo.findByPidJournalOrderByLineNoAsc(j.getPidJournal());
        if (lines.size() < 2) throw bad("A journal needs at least two lines.");
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (AccJournalLine l : lines) { dr = dr.add(l.getDebit()); cr = cr.add(l.getCredit()); }
        if (dr.compareTo(cr) != 0) throw new BusinessException("Journal " + j.getJournalNo() + " does not balance: debit " + dr + " vs credit " + cr + ".", HttpStatus.CONFLICT, "ACC_UNBALANCED");
        AccPeriod period = periods.periodFor(j.getPidOrg(), j.getJournalDate());
        periods.requireOpenFor(period, j.getJournalType());
        for (AccJournalLine l : lines) {
            AccAccount a = chart.owned(j.getPidOrg(), l.getPidAccount());
            if (Integer.valueOf(1).equals(a.getIsGroup())) throw bad(a.getAccountCode() + " " + a.getAccountName() + " is a group account.");
            if (!Integer.valueOf(1).equals(a.getIsActive())) throw bad(a.getAccountCode() + " " + a.getAccountName() + " is retired.");
            if (!Integer.valueOf(1).equals(a.getAllowManual()) && !"OPENING".equals(j.getJournalType()))
                throw bad(a.getAccountCode() + " " + a.getAccountName() + " is posted by the system only; use the business screen that owns it.");
            if ("CASH_REGISTER".equals(a.getRoleKey())) requireDayOpen(j.getPidOrg(), l.getPidBranch(), j.getJournalDate());
        }
        j.setPidPeriod(period.getPidPeriod());
        j.setTotalDebit(dr); j.setTotalCredit(cr);
        j.setJournalStatus("POSTED"); j.setPostedBy(scope.username()); j.setPostedAt(LocalDateTime.now());
        ledgerVersion.bump(j.getPidOrg());
        j.setApprovedBy(scope.username()); j.setApprovedAt(LocalDateTime.now());
        journalRepo.save(j);
        AccEvent ev = eventRepo.save(AccEvent.builder().pidOrg(j.getPidOrg()).pidBranch(j.getPidBranch()).sourceModule("MANUAL").sourceType("JOURNAL")
                .sourceId(j.getPidJournal()).eventKey("POSTED").eventType(j.getJournalType() + "_JOURNAL").eventDate(j.getJournalDate())
                .reference(j.getReference()).description(j.getDescription()).amount(dr).eventStatus("POSTED").pidJournal(j.getPidJournal())
                .processedAt(LocalDateTime.now()).retryCount(0).build());
        j.setPidEvent(ev.getPidEvent());
        return journalRepo.save(j);
    }

    /** Reverses a posted journal, any source, with a reason. */
    @Transactional
    public AccJournal reverse(Long id, LocalDate date, String reason) {
        scope.require(AccScope.MOD_JOURNALS, PermissionGuard.Right.APPROVE, "reverse a journal");
        AccJournal j = owned(id);
        AccEvent ev = j.getPidEvent() == null ? null : eventRepo.findById(j.getPidEvent()).orElse(null);
        return engine.reverseJournal(j, date, reason, ev);
    }

    private void requireDayOpen(Long org, Long branch, LocalDate date) {
        Optional<AccBusinessDay> day = dayRepo.findByPidBranchAndBusinessDate(branch, date);
        if (day.isPresent() && Set.of("CLOSED", "LOCKED", "SUBMITTED").contains(day.get().getDayStatus())) {
            throw new BusinessException("The cash day " + date + " is " + day.get().getDayStatus().toLowerCase() + "; a journal moving the till cannot be dated on it.", HttpStatus.CONFLICT, "ACC_DAY_CLOSED");
        }
        if (settings.is(org, AccSettings.REQUIRE_DAY_OPEN) && day.isEmpty()) {
            throw new BusinessException("The cash day " + date + " has not been opened.", HttpStatus.CONFLICT, "ACC_DAY_NOT_OPEN");
        }
    }

    private void validate(Long org, JournalIn in) {
        if (in.lines == null || in.lines.size() < 2) throw bad("A journal needs at least two lines.");
        for (LineIn l : in.lines) {
            if (l.accountId == null) throw bad("Every line needs an account.");
            BigDecimal d = nvl(l.debit), c = nvl(l.credit);
            if (d.signum() < 0 || c.signum() < 0) throw bad("Debit and credit cannot be negative.");
            if (d.signum() > 0 && c.signum() > 0) throw bad("A line is either a debit or a credit, not both.");
            if (d.signum() == 0 && c.signum() == 0) throw bad("A line needs an amount.");
            chart.owned(org, l.accountId);
        }
        BigDecimal[] t = totals(in);
        if (t[0].compareTo(t[1]) != 0) throw new BusinessException("The journal does not balance: debit " + t[0] + " vs credit " + t[1] + ".", HttpStatus.UNPROCESSABLE_ENTITY, "ACC_UNBALANCED");
    }

    private BigDecimal[] totals(JournalIn in) {
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (LineIn l : in.lines) { d = d.add(nvl(l.debit)); c = c.add(nvl(l.credit)); }
        return new BigDecimal[]{ d, c };
    }

    private void writeLines(AccJournal j, JournalIn in, LocalDate date) {
        int n = 1;
        for (LineIn l : in.lines) {
            lineRepo.save(AccJournalLine.builder().pidJournal(j.getPidJournal()).lineNo(n++).pidAccount(l.accountId)
                    .debit(nvl(l.debit)).credit(nvl(l.credit)).description(AccountingEventService.trim(l.description, 300))
                    .pidBranch(j.getPidBranch()).pidCostCenter(l.costCenterId != null ? l.costCenterId : in.costCenterId)
                    .partyType(l.partyType).partyId(l.partyId).partyName(AccountingEventService.trim(l.partyName, 150))
                    .journalDate(date).pidOrg(j.getPidOrg()).build());
        }
    }

    private AccJournal owned(Long id) {
        AccJournal j = journalRepo.findByPidJournalAndPidOrg(id, scope.orgId())
                .orElseThrow(() -> new BusinessException("Journal not found.", HttpStatus.NOT_FOUND, "ACC_JOURNAL_NOT_FOUND"));
        Long branch = scope.branchFilter(null);
        if (branch != null && !branch.equals(j.getPidBranch())) throw new BusinessException("Journal not found.", HttpStatus.NOT_FOUND, "ACC_JOURNAL_NOT_FOUND");
        return j;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP); }
    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
