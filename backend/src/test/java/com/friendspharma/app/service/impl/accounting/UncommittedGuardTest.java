package com.friendspharma.app.service.impl.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A cache filled inside a transaction is dropped when that transaction does not commit. */
class UncommittedGuardTest {

    @AfterEach
    void clean() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }

    @Test
    void evictsOnRollback() {
        AtomicInteger evictions = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        UncommittedGuard.evictUnlessCommitted(evictions::incrementAndGet);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(1, evictions.get());
    }

    @Test
    void keepsOnCommit() {
        AtomicInteger evictions = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        UncommittedGuard.evictUnlessCommitted(evictions::incrementAndGet);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertEquals(0, evictions.get());
    }

    @Test
    void evictsWhenTheOutcomeIsUnknown() {
        AtomicInteger evictions = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        UncommittedGuard.evictUnlessCommitted(evictions::incrementAndGet);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        assertEquals(1, evictions.get());
    }

    @Test
    void noOpOutsideATransaction() {
        AtomicInteger evictions = new AtomicInteger();
        UncommittedGuard.evictUnlessCommitted(evictions::incrementAndGet);   // must not throw, nothing to register against
        assertEquals(0, evictions.get());
    }
}
