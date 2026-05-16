package com.sqlplayground.engine.mvcc;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MVCC Transaction Manager.
 * Tracks active transactions via session IDs (for multi-request transactions).
 * When getCurrentTxn() returns 0, the system operates in auto-commit mode.
 */
@Component
public class TransactionManager {

    private final AtomicLong txnCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, String> activeTxns = new ConcurrentHashMap<>(); // txnId -> status
    private final ConcurrentHashMap<String, Long> sessionTxns = new ConcurrentHashMap<>(); // sessionId -> txnId

    /**
     * Begin a new transaction for the given session.
     */
    public long begin(String sessionId) {
        if (sessionTxns.containsKey(sessionId)) {
            throw new IllegalStateException("Session already has an active transaction: " + sessionTxns.get(sessionId));
        }
        long txnId = txnCounter.getAndIncrement();
        activeTxns.put(txnId, "ACTIVE");
        sessionTxns.put(sessionId, txnId);
        return txnId;
    }

    /**
     * Commit a transaction for the given session.
     */
    public long commit(String sessionId) {
        Long txnId = sessionTxns.remove(sessionId);
        if (txnId == null) throw new IllegalStateException("No active transaction for this session");
        activeTxns.put(txnId, "COMMITTED");
        return txnId;
    }

    /**
     * Rollback a transaction for the given session.
     */
    public long rollback(String sessionId) {
        Long txnId = sessionTxns.remove(sessionId);
        if (txnId == null) throw new IllegalStateException("No active transaction for this session");
        activeTxns.put(txnId, "ROLLED_BACK");
        return txnId;
    }

    /**
     * Get the current transaction ID for a session, or 0 if auto-commit.
     */
    public long getCurrentTxn(String sessionId) {
        if (sessionId == null) return 0;
        Long txnId = sessionTxns.get(sessionId);
        return txnId != null ? txnId : 0;
    }

    /**
     * Check if a transaction is active.
     */
    public boolean isActive(long txnId) {
        return "ACTIVE".equals(activeTxns.get(txnId));
    }

    /**
     * Check if a row version is visible to a given reader transaction.
     */
    public boolean isVisible(RowVersion rv, long readerTxnId) {
        // Created by a committed transaction or by the current transaction
        boolean createdVisible = rv.createdByTxn == readerTxnId
            || "COMMITTED".equals(activeTxns.get(rv.createdByTxn));

        // Not deleted, or deleted by a rolled-back transaction, or deleted by a different active txn
        boolean notDeleted = rv.deletedByTxn == 0
            || "ROLLED_BACK".equals(activeTxns.get(rv.deletedByTxn))
            || (rv.deletedByTxn != readerTxnId && isActive(rv.deletedByTxn));

        return createdVisible && notDeleted;
    }

    /**
     * Get summary of all transactions for the API.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nextTxnId", txnCounter.get());
        summary.put("activeSessions", new LinkedHashMap<>(sessionTxns));

        List<Map<String, Object>> txnList = new ArrayList<>();
        for (Map.Entry<Long, String> e : activeTxns.entrySet()) {
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("txnId", e.getKey());
            txn.put("status", e.getValue());
            txnList.add(txn);
        }
        summary.put("transactions", txnList);
        return summary;
    }

    /**
     * Clear all transaction state (for reset).
     */
    public void clear() {
        activeTxns.clear();
        sessionTxns.clear();
    }
}
