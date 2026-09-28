package com.friendspharma.app.service.impl.accounting;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A counter per organisation that moves every time its ledger changes.
 *
 * <p>The dashboard reads the whole ledger to draw its tiles and keeps the
 * result until this counter moves, so a screen that polls every minute costs
 * the database nothing while nobody posts. The counter is bumped when the
 * transaction that wrote the journal commits — not before, or a reader could
 * cache the books as they stood a moment before the write and carry that
 * version number as if it were current.
 *
 * <p>In-process only: with more than one backend instance each keeps its own
 * count, and the other instance's cache ages out on its time limit instead.
 */
@Component
public class AccLedgerVersion {

    private final Map<Long, AtomicLong> versions = new ConcurrentHashMap<>();

    public long get(Long orgId) {
        return versions.computeIfAbsent(orgId, k -> new AtomicLong()).get();
    }

    /** The ledger of this organisation has changed (or will have, once the current transaction commits). */
    public void bump(Long orgId) {
        if (orgId == null) return;
        AtomicLong v = versions.computeIfAbsent(orgId, k -> new AtomicLong());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { v.incrementAndGet(); }
            });
        } else {
            v.incrementAndGet();
        }
    }
}
