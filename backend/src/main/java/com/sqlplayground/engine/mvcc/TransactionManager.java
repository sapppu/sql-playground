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
     * Check if a session has an active transaction.
     */
    public boolean hasActiveTxn(String sessionId) {
        return sessionId != null && sessionTxns.containsKey(sessionId);
    }

    /**
     * Get current txn ID for a session (alias for getCurrentTxn).
     */
    public long getCurrentTxnId(String sessionId) {
        return getCurrentTxn(sessionId);
    }

    /**
     * Check if a transaction is active.
     */
    public boolean isActive(long txnId) {
        return "ACTIVE".equals(activeTxns.get(txnId));
    }

    /**
     * A row version is visible to a reader if:
     * 1. It was created by a committed transaction (or the reader's own txn)
     * 2. It has NOT been deleted by a committed transaction
     * 3. It was NOT created by a rolled-back transaction
     */
    public boolean isVisible(RowVersion rv, long readerTxnId) {
        // Row created by this reader's own transaction — always visible
        if (rv.createdByTxn == readerTxnId) {
            // But only if not deleted by self
            return rv.deletedByTxn == 0 || rv.deletedByTxn == readerTxnId;
        }

        // Row created by a rolled-back transaction — never visible
        String creatorStatus = activeTxns.get(rv.createdByTxn);
        if ("ROLLED_BACK".equals(creatorStatus)) {
            return false;
        }

        // Row created by an active (uncommitted) transaction — not visible to others
        if ("ACTIVE".equals(creatorStatus)) {
            return false;
        }

        // Row created by a committed transaction — check if it was deleted
        if (rv.deleted) {
            // Deleted by this reader's own txn — not visible
            if (rv.deletedByTxn == readerTxnId) return false;

            // Deleted by a rolled-back txn — deletion doesn't count, row is visible
            String deleterStatus = activeTxns.get(rv.deletedByTxn);
            if ("ROLLED_BACK".equals(deleterStatus)) return true;

            // Deleted by an active txn — deletion not yet committed, row still visible
            if ("ACTIVE".equals(deleterStatus)) return true;

            // Deleted by a committed txn — row is gone
            return false;
        }

        // Created by committed txn, not deleted — visible
        return true;
    }

    public boolean isRolledBack(long txnId) {
        return "ROLLED_BACK".equals(activeTxns.get(txnId));
    }

    public boolean isCommitted(long txnId) {
        return !activeTxns.containsKey(txnId) || "COMMITTED".equals(activeTxns.get(txnId));
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
