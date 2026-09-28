package com.friendspharma.app.service.impl.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.DocumentNumberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The accounting engine: a financial event in, a balanced posted journal out.
 *
 * <pre>
 *   FinancialEvent → acc_event (unique per source) → rule → lines → acc_journal (POSTED)
 * </pre>
 *
 * <p><b>Synchronous and transactional.</b> A business module calls
 * {@link #post} inside its own transaction. If the journal cannot be built —
 * a role unmapped, a period locked, a closed day, an unbalanced result — a
 * {@link BusinessException} propagates and the business transaction rolls
 * back with it: there is never a sale without its journal, nor a journal
 * without its sale. The failure itself is recorded in its own transaction
 * ({@link AccEventFailureRecorder}) so the event monitor can show it.
 *
 * <p><b>Idempotent.</b> The same source and event key posts once. A second
 * call finds the POSTED event and returns it. A source that changed is
 * reversed and re-posted under a new event key (a revision) by the module.
 *
 * <p><b>Immutable.</b> A posted journal is never updated; correction is a
 * reversal journal, and the database trigger enforces it behind this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingEventService {

    public static final String POSTED = "POSTED", PENDING = "PENDING", FAILED = "FAILED", SKIPPED = "SKIPPED", REVERSED = "REVERSED";

    private final AccEventRepository        eventRepo;
    private final AccRuleRepository         ruleRepo;
    private final AccRuleLineRepository     ruleLineRepo;
    private final AccJournalRepository      journalRepo;
    private final AccJournalLineRepository  lineRepo;
    private final AccAccountRepository      accountRepo;
    private final AccBusinessDayRepository  dayRepo;
    private final AccChartService           chart;
    private final AccPeriodService          periods;
    private final AccSettings               settings;
    private final DocumentNumberService     numbers;
    private final AccEventFailureRecorder   failures;
    private final AccLedgerVersion          ledgerVersion;
    private final ObjectMapper              json;

    /** Rule lines per organisation and event type — rules change through one screen, which evicts. */
    private final java.util.concurrent.ConcurrentMap<String, List<AccRuleLine>> ruleCache = new java.util.concurrent.ConcurrentHashMap<>();
    public void evictRules() { ruleCache.clear(); }

    /* ══════════════════════════════════════════════════════════════════════
       POST
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Posts an event. Returns the journal, or null when the organisation has
     * posting switched off (the event is then recorded SKIPPED so it can be
     * replayed once the books are started).
     */
    @Transactional
    public AccJournal post(FinancialEvent ev) {
        require(ev);
        Optional<AccEvent> existing = eventRepo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(
                ev.orgId, ev.sourceModule, ev.sourceType, ev.sourceId, ev.eventKey);
        if (existing.isPresent() && POSTED.equals(existing.get().getEventStatus())) {
            log.debug("Accounting event {} already posted as journal {}", ev.identity(), existing.get().getPidJournal());
            return journalRepo.findById(existing.get().getPidJournal()).orElse(null);
        }
        if (existing.isPresent() && REVERSED.equals(existing.get().getEventStatus())) {
            throw new BusinessException("Accounting event " + ev.identity() + " was reversed; re-post it under a new revision key.",
                    HttpStatus.CONFLICT, "ACC_EVENT_REVERSED");
        }
        AccEvent event = existing.orElseGet(() -> AccEvent.builder()
                .pidOrg(ev.orgId).pidBranch(ev.branchId).sourceModule(ev.sourceModule).sourceType(ev.sourceType)
                .sourceId(ev.sourceId).eventKey(ev.eventKey).retryCount(0).build());
        event.setEventType(ev.eventType);
        event.setEventDate(ev.date);
        event.setReference(trim(ev.reference, 60));
        event.setDescription(trim(ev.description, 300));
        event.setAmount(ev.headline().setScale(2, RoundingMode.HALF_UP));
        event.setPayload(payloadOf(ev));
        event.setPidBackfillBatch(ev.backfillBatchId);
        if (existing.isPresent()) event.setRetryCount((event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1);

        if (!settings.is(ev.orgId, AccSettings.POSTING_ENABLED)) {
            event.setEventStatus(SKIPPED);
            event.setErrorMessage("Posting is switched off for this organisation.");
            event.setProcessedAt(LocalDateTime.now());
            eventRepo.save(event);
            return null;
        }

        try {
            chart.ensureProvisioned(ev.orgId);
            AccJournal journal = buildAndPost(ev, event.getPidBackfillBatch() != null ? "AUTO" : ev.journalType);
            if (journal == null) {
                event.setEventStatus(SKIPPED);
                event.setErrorMessage("No amounts to post (zero-value document).");
                event.setProcessedAt(LocalDateTime.now());
                eventRepo.save(event);
                return null;
            }
            event.setEventStatus(POSTED);
            event.setPidJournal(journal.getPidJournal());
            event.setErrorMessage(null);
            event.setProcessedAt(LocalDateTime.now());
            eventRepo.save(event);
            journal.setPidEvent(event.getPidEvent());
            journalRepo.save(journal);
            return journal;
        } catch (BusinessException e) {
            failures.record(ev, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Accounting event {} failed", ev.identity(), e);
            failures.record(ev, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new BusinessException("Unable to post the accounting transaction. The transaction has not been completed.",
                    HttpStatus.INTERNAL_SERVER_ERROR, "ACC_POSTING_FAILED");
        }
    }

    private AccJournal buildAndPost(FinancialEvent ev, String journalType) {
        List<AccRuleLine> ruleLines = ruleCache.computeIfAbsent(ev.orgId + "/" + ev.eventType, k -> {
            AccRule rule = ruleRepo.findEffective(ev.orgId, ev.eventType).stream().findFirst().orElseThrow(() ->
                    new BusinessException("No accounting rule is configured for '" + ev.eventType + "'. Add one under Periods & Settings › Accounting rules.",
                            HttpStatus.CONFLICT, "ACC_RULE_MISSING"));
            return ruleLineRepo.findByPidRuleOrderByLineNoAsc(rule.getPidRule());
        });
        if (ruleLines.isEmpty()) throw new BusinessException("Accounting rule for '" + ev.eventType + "' has no lines.", HttpStatus.CONFLICT, "ACC_RULE_EMPTY");

        List<AccJournalLine> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (AccRuleLine rl : ruleLines) {
            BigDecimal amount = money(ev.amounts.get(rl.getAmountKey()));
            if (amount.signum() == 0) continue;
            // A negative amount swaps the side rather than writing a negative line.
            String side = rl.getSide();
            if (amount.signum() < 0) { amount = amount.negate(); side = "D".equals(side) ? "C" : "D"; }
            AccAccount account = resolveAccount(ev, rl, amount);
            if (account == null) continue;
            List<FinancialEvent.Split> parts = ev.splits.get(rl.getAmountKey());
            if (parts == null || parts.isEmpty()) {
                lines.add(line(ev, rl, account, side, amount, ev.partyType, ev.partyId, ev.partyName));
                if ("D".equals(side)) debit = debit.add(amount); else credit = credit.add(amount);
            } else {
                for (FinancialEvent.Split p : parts) {
                    BigDecimal a = money(p.amount());
                    String s = side;
                    if (a.signum() < 0) { a = a.negate(); s = "D".equals(s) ? "C" : "D"; }
                    if (a.signum() == 0) continue;
                    lines.add(line(ev, rl, account, s, a, p.partyType(), p.partyId(), p.partyName()));
                    if ("D".equals(s)) debit = debit.add(a); else credit = credit.add(a);
                }
            }
        }
        if (lines.isEmpty()) return null;   // nothing to book: a zero-value document
        if (debit.compareTo(credit) != 0) {
            throw new BusinessException("Accounting for " + ev.eventType + " (" + ev.reference + ") does not balance: debit "
                    + debit.toPlainString() + " vs credit " + credit.toPlainString() + ". Nothing was posted.",
                    HttpStatus.CONFLICT, "ACC_UNBALANCED");
        }
        LocalDate date = journalDate(ev, lines);
        AccPeriod period = periods.periodFor(ev.orgId, date);
        periods.requireOpenFor(period, journalType);

        AccJournal j = AccJournal.builder()
                .pidOrg(ev.orgId).pidBranch(ev.branchId).journalNo(numbers.nextNumber("SEQ_JOURNAL_NO", "JV"))
                .journalDate(date).pidPeriod(period.getPidPeriod()).journalType(journalType)
                .sourceModule(ev.sourceModule).sourceType(ev.sourceType).sourceId(ev.sourceId)
                .reference(trim(ev.reference, 60)).description(trim(ev.description, 300))
                .pidCostCenter(ev.costCenterId).currency(settings.get(ev.orgId, AccSettings.CURRENCY))
                .totalDebit(debit).totalCredit(credit).journalStatus(POSTED)
                .postedBy(actor()).postedAt(LocalDateTime.now()).build();
        j = journalRepo.save(j);
        int n = 1;
        for (AccJournalLine l : lines) {
            l.setPidJournal(j.getPidJournal()); l.setLineNo(n++); l.setJournalDate(date);
            lineRepo.save(l);
        }
        ledgerVersion.bump(ev.orgId);
        return j;
    }

    private AccJournalLine line(FinancialEvent ev, AccRuleLine rl, AccAccount account, String side, BigDecimal amount,
                                String partyType, Long partyId, String partyName) {
        boolean carriesParty = rl.getPartyType() != null && partyId != null;
        return AccJournalLine.builder()
                .pidAccount(account.getPidAccount())
                .debit("D".equals(side) ? amount : BigDecimal.ZERO).credit("C".equals(side) ? amount : BigDecimal.ZERO)
                .description(trim(ev.description != null && !ev.description.isBlank() ? ev.description : rl.getDescription(), 300))
                .pidBranch(ev.branchId).pidCostCenter(ev.costCenterId)
                .partyType(carriesParty ? (rl.getPartyType() != null ? rl.getPartyType() : partyType) : null)
                .partyId(carriesParty ? partyId : null).partyName(carriesParty ? trim(partyName, 150) : null)
                .pidOrg(ev.orgId).build();
    }

    /** A rule line's account: a role, the payment mode's cash account, or one the event named. */
    private AccAccount resolveAccount(FinancialEvent ev, AccRuleLine rl, BigDecimal amount) {
        String role = rl.getAccountRole();
        if (role.startsWith("@PAYLOAD:")) {
            String key = role.substring("@PAYLOAD:".length());
            Long pid = ev.accounts.get(key);
            if (pid == null) throw new BusinessException("Accounting event " + ev.eventType + " named no account for '" + key + "'.", HttpStatus.CONFLICT, "ACC_ACCOUNT_MISSING");
            return postable(chart.cachedById(ev.orgId, pid).orElseThrow(() ->
                    new BusinessException("Account " + pid + " is not in this organisation's chart.", HttpStatus.CONFLICT, "ACC_ACCOUNT_NOT_FOUND")));
        }
        if (role.startsWith("@MODE")) {
            Optional<AccAccount> byMode = chart.byPaymentMode(ev.orgId, ev.paymentMode);
            if (byMode.isPresent()) return postable(byMode.get());
            // A non-cash mode (CREDIT) with money attached: the money was real, so it went to the till.
            String fallback = role.contains("|") ? role.substring(role.indexOf('|') + 1) : "CASH_REGISTER";
            return postable(chart.byRole(ev.orgId, fallback));
        }
        return postable(chart.byRole(ev.orgId, role));
    }

    private AccAccount postable(AccAccount a) {
        if (Integer.valueOf(1).equals(a.getIsGroup())) {
            throw new BusinessException(a.getAccountCode() + " " + a.getAccountName() + " is a group account; journals post to its children only.", HttpStatus.CONFLICT, "ACC_GROUP_ACCOUNT");
        }
        if (!Integer.valueOf(1).equals(a.getIsActive())) {
            throw new BusinessException(a.getAccountCode() + " " + a.getAccountName() + " is retired and cannot take a posting.", HttpStatus.CONFLICT, "ACC_ACCOUNT_RETIRED");
        }
        return a;
    }

    /**
     * The date the journal is booked on. A posting that moves the branch till
     * on a CLOSED business day is refused (or dated forward, by policy) — the
     * day sheet that was signed off must keep meaning what it said.
     */
    private LocalDate journalDate(FinancialEvent ev, List<AccJournalLine> lines) {
        LocalDate date = ev.date != null ? ev.date : LocalDate.now();
        if (ev.historical) return date;
        boolean touchesTill = lines.stream().anyMatch(l -> isTill(ev.orgId, l.getPidAccount()));
        if (!touchesTill) return date;
        Optional<AccBusinessDay> day = dayRepo.findByPidBranchAndBusinessDate(ev.branchId, date);
        boolean closed = day.isPresent() && Set.of("CLOSED", "LOCKED", "SUBMITTED").contains(day.get().getDayStatus());
        if (closed) {
            String policy = settings.get(ev.orgId, AccSettings.LATE_POSTING_POLICY);
            if ("NEXT_DAY".equalsIgnoreCase(policy)) {
                LocalDate d = date.plusDays(1);
                for (int i = 0; i < 366; i++, d = d.plusDays(1)) {
                    Optional<AccBusinessDay> x = dayRepo.findByPidBranchAndBusinessDate(ev.branchId, d);
                    if (x.isEmpty() || !Set.of("CLOSED", "LOCKED", "SUBMITTED").contains(x.get().getDayStatus())) return d;
                }
            }
            throw new BusinessException("The cash day " + date + " is already " + day.get().getDayStatus().toLowerCase()
                    + " for this branch. Date the transaction today, or have the day reopened.",
                    HttpStatus.CONFLICT, "ACC_DAY_CLOSED");
        }
        if (settings.is(ev.orgId, AccSettings.REQUIRE_DAY_OPEN) && day.isEmpty()) {
            throw new BusinessException("The cash day " + date + " has not been opened for this branch. Open the day on Daily Cash first.",
                    HttpStatus.CONFLICT, "ACC_DAY_NOT_OPEN");
        }
        return date;
    }

    private boolean isTill(Long orgId, Long pidAccount) {
        return "CASH_REGISTER".equals(chart.roleOf(orgId, pidAccount));
    }

    /* ══════════════════════════════════════════════════════════════════════
       REVERSAL
       ══════════════════════════════════════════════════════════════════════ */

    /** Reverses every posted journal of a source transaction. Returns how many. */
    @Transactional
    public int reverseSource(Long orgId, String sourceModule, String sourceType, Long sourceId, LocalDate date, String reason) {
        int n = 0;
        for (AccEvent e : eventRepo.findBySourceModuleAndSourceTypeAndSourceIdOrderByPidEventAsc(sourceModule, sourceType, sourceId)) {
            if (!POSTED.equals(e.getEventStatus()) || !orgId.equals(e.getPidOrg())) continue;
            AccJournal original = journalRepo.findById(e.getPidJournal()).orElse(null);
            if (original == null || !POSTED.equals(original.getJournalStatus())) continue;
            reverseJournal(original, date, reason, e);
            n++;
        }
        return n;
    }

    /** Reverses one posted journal with a stated reason; returns the reversal. */
    @Transactional
    public AccJournal reverseJournal(AccJournal original, LocalDate date, String reason, AccEvent event) {
        if (!POSTED.equals(original.getJournalStatus())) {
            throw new BusinessException("Journal " + original.getJournalNo() + " is " + original.getJournalStatus().toLowerCase() + " and cannot be reversed.",
                    HttpStatus.CONFLICT, "ACC_NOT_POSTED");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Say why " + original.getJournalNo() + " is being reversed — the reason is the part of the trail that outlives everyone.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "ACC_REASON_REQUIRED");
        }
        LocalDate when = date != null ? date : LocalDate.now();
        AccPeriod period = periods.periodFor(original.getPidOrg(), when);
        periods.requireOpenFor(period, "REVERSAL");
        AccJournal rev = AccJournal.builder()
                .pidOrg(original.getPidOrg()).pidBranch(original.getPidBranch()).journalNo(numbers.nextNumber("SEQ_JOURNAL_NO", "JV"))
                .journalDate(when).pidPeriod(period.getPidPeriod()).journalType("REVERSAL")
                .sourceModule(original.getSourceModule()).sourceType(original.getSourceType()).sourceId(original.getSourceId())
                .reference(original.getReference()).description(trim("Reversal of " + original.getJournalNo() + ": " + reason, 300))
                .pidCostCenter(original.getPidCostCenter()).currency(original.getCurrency())
                .totalDebit(original.getTotalCredit()).totalCredit(original.getTotalDebit()).journalStatus(POSTED)
                .reversalOf(original.getPidJournal()).reversalReason(trim(reason, 500))
                .postedBy(actor()).postedAt(LocalDateTime.now()).build();
        rev = journalRepo.save(rev);
        int n = 1;
        for (AccJournalLine l : lineRepo.findByPidJournalOrderByLineNoAsc(original.getPidJournal())) {
            lineRepo.save(AccJournalLine.builder().pidJournal(rev.getPidJournal()).lineNo(n++).pidAccount(l.getPidAccount())
                    .debit(l.getCredit()).credit(l.getDebit()).description(l.getDescription()).pidBranch(l.getPidBranch())
                    .pidCostCenter(l.getPidCostCenter()).partyType(l.getPartyType()).partyId(l.getPartyId()).partyName(l.getPartyName())
                    .journalDate(when).pidOrg(l.getPidOrg()).build());
        }
        ledgerVersion.bump(original.getPidOrg());
        original.setJournalStatus(REVERSED);
        original.setReversedBy(rev.getPidJournal());
        original.setReversedByUser(actor());
        original.setReversedAt(LocalDateTime.now());
        original.setReversalReason(trim(reason, 500));
        journalRepo.save(original);
        if (event != null) {
            event.setEventStatus(REVERSED);
            eventRepo.save(event);
            AccEvent trail = AccEvent.builder().pidOrg(event.getPidOrg()).pidBranch(event.getPidBranch())
                    .sourceModule(event.getSourceModule()).sourceType(event.getSourceType()).sourceId(event.getSourceId())
                    .eventKey(uniqueRevKey(event)).eventType(event.getEventType() + "_REVERSAL").eventDate(when)
                    .reference(event.getReference()).description(trim("Reversal: " + reason, 300)).amount(event.getAmount())
                    .eventStatus(POSTED).pidJournal(rev.getPidJournal()).processedAt(LocalDateTime.now()).retryCount(0).build();
            eventRepo.save(trail);
            rev.setPidEvent(trail.getPidEvent());
            journalRepo.save(rev);
        }
        return rev;
    }

    private String uniqueRevKey(AccEvent e) {
        String base = trim(e.getEventKey() + ":REV", 34);
        for (int i = 1; i < 100; i++) {
            String k = i == 1 ? base : base + i;
            if (eventRepo.findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(e.getPidOrg(), e.getSourceModule(), e.getSourceType(), e.getSourceId(), k).isEmpty()) return k;
        }
        return base + System.nanoTime() % 1000;
    }

    /** The next revision key for a source whose figures changed: POSTED, POSTED:R2, POSTED:R3 ... */
    @Transactional(readOnly = true)
    public String nextRevisionKey(Long orgId, String sourceModule, String sourceType, Long sourceId, String baseKey) {
        int n = 1;
        for (AccEvent e : eventRepo.findBySourceModuleAndSourceTypeAndSourceIdOrderByPidEventAsc(sourceModule, sourceType, sourceId)) {
            if (orgId.equals(e.getPidOrg()) && e.getEventKey().startsWith(baseKey) && !e.getEventKey().contains(":REV")) n++;
        }
        return n == 1 ? baseKey : baseKey + ":R" + n;
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private void require(FinancialEvent ev) {
        if (ev.orgId == null || ev.branchId == null) throw new BusinessException("An accounting event needs an organisation and a branch.", HttpStatus.BAD_REQUEST, "ACC_VALIDATION");
        if (ev.sourceModule == null || ev.sourceType == null || ev.sourceId == null || ev.eventKey == null || ev.eventType == null)
            throw new BusinessException("An accounting event needs its source identity and type.", HttpStatus.BAD_REQUEST, "ACC_VALIDATION");
        if (ev.date == null) ev.date = LocalDate.now();
    }

    private String payloadOf(FinancialEvent ev) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("amounts", ev.amounts); m.put("accounts", ev.accounts); m.put("mode", ev.paymentMode);
            m.put("party", ev.partyType == null ? null : ev.partyType + ":" + ev.partyId + ":" + ev.partyName);
            m.put("splits", ev.splits.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, x -> x.getValue().size())));
            return json.writeValueAsString(m);
        } catch (Exception e) { return null; }
    }

    static BigDecimal money(BigDecimal v) { return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP); }
    static String trim(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }
    static String actor() { String u = SecurityUtils.getCurrentUsername(); return u == null ? "system" : u; }
}
