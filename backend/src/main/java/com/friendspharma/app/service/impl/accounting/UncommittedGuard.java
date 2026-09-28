package com.friendspharma.app.service.impl.accounting;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps an in-memory cache from outliving the transaction that filled it.
 *
 * <p>The chart and the periods are cached per organisation so a posting does
 * not query for them. Both are also created on demand, inside the posting's
 * own transaction: the first purchase an organisation ever approves inserts
 * its chart of accounts and its fiscal year, and the cache is filled from
 * those rows before they are committed. When that transaction then rolls
 * back — a bad line, a refused stock move, anything — the rows vanish but the
 * cache keeps them, and every later posting for the organisation carries ids
 * that the database has never seen: {@code violates foreign key constraint
 * fk_acc_journal_period ... Key (pid_period)=(9) is not present}. The only
 * way out was a restart.
 *
 * <p>A cache fill that happens while a transaction is active therefore
 * registers an eviction that runs if that transaction does not commit. A
 * fill outside any transaction is already reading committed data.
 */
final class UncommittedGuard {

    private UncommittedGuard() {}

    /** Runs {@code evict} after the current transaction completes, unless it committed. No-op outside a transaction. */
    static void evictUnlessCommitted(Runnable evict) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) evict.run();
            }
        });
    }
}
