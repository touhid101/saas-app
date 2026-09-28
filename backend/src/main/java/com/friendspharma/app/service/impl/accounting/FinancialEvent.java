package com.friendspharma.app.service.impl.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * What a business module tells Accounts when something financial happened.
 *
 * <p>Deliberately free of accounts, debits and credits: the module says what
 * happened (a sale of this much, paid this way, to this customer) and the
 * accounting rule decides where it goes. The pair {@code sourceModule /
 * sourceType / sourceId / eventKey} is the identity that makes posting
 * idempotent; {@code eventType} names the rule.
 *
 * <p>Amounts are keyed the way the rule lines read them ({@code REVENUE},
 * {@code COGS}, {@code AMOUNT} ...). An amount may be split by party — one
 * journal line per employee on a payroll, for instance — through
 * {@link #split(String, String, Long, String, BigDecimal)}. Where the account
 * depends on the transaction rather than the rule (a refund by CASH or by
 * customer credit), the module names it through {@link #account(String, Long)}
 * and the rule line reads {@code @PAYLOAD:<key>}.
 */
public final class FinancialEvent {

    public record Split(String partyType, Long partyId, String partyName, BigDecimal amount) {}

    public String sourceModule, sourceType, eventKey, eventType;
    public Long sourceId, orgId, branchId, costCenterId, backfillBatchId;
    public LocalDate date;
    public String reference, description, paymentMode;
    public String partyType, partyName;
    public Long partyId;
    /** Journal type: AUTO for everything a module raises; the engine sets REVERSAL / OPENING itself. */
    public String journalType = "AUTO";
    /** True for a historical backfill: business-day closure checks are skipped, periods are still honoured. */
    public boolean historical;
    public final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    public final Map<String, Long> accounts = new LinkedHashMap<>();
    public final Map<String, List<Split>> splits = new LinkedHashMap<>();

    public static FinancialEvent of(String sourceModule, String sourceType, Long sourceId, String eventKey, String eventType) {
        FinancialEvent e = new FinancialEvent();
        e.sourceModule = sourceModule; e.sourceType = sourceType; e.sourceId = sourceId; e.eventKey = eventKey; e.eventType = eventType;
        return e;
    }

    public FinancialEvent in(Long orgId, Long branchId) { this.orgId = orgId; this.branchId = branchId; return this; }
    public FinancialEvent on(LocalDate date) { this.date = date; return this; }
    public FinancialEvent ref(String reference, String description) { this.reference = reference; this.description = description; return this; }
    public FinancialEvent mode(String paymentMode) { this.paymentMode = paymentMode; return this; }
    public FinancialEvent party(String type, Long id, String name) { this.partyType = type; this.partyId = id; this.partyName = name; return this; }
    public FinancialEvent costCenter(Long id) { this.costCenterId = id; return this; }
    public FinancialEvent historical(Long batchId) { this.historical = true; this.backfillBatchId = batchId; return this; }
    public FinancialEvent amount(String key, BigDecimal value) { if (value != null) amounts.put(key, value); return this; }
    public FinancialEvent account(String key, Long pidAccount) { if (pidAccount != null) accounts.put(key, pidAccount); return this; }
    public FinancialEvent split(String amountKey, String partyType, Long partyId, String partyName, BigDecimal amount) {
        if (amount == null || amount.signum() == 0) return this;
        splits.computeIfAbsent(amountKey, k -> new ArrayList<>()).add(new Split(partyType, partyId, partyName, amount));
        amounts.merge(amountKey, amount, BigDecimal::add);
        return this;
    }

    public BigDecimal amount(String key) { return amounts.getOrDefault(key, BigDecimal.ZERO); }

    /** The largest amount, for the event register. */
    public BigDecimal headline() {
        return amounts.values().stream().filter(Objects::nonNull).map(BigDecimal::abs).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    public String identity() { return sourceModule + "/" + sourceType + "/" + sourceId + "/" + eventKey; }
}
