package com.sqlplayground.engine.mvcc;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MVCC Transaction Manager.
 * Tracks active transactions via session IDs (for multi-request transactions).
 * When getCurrentTxn() returns 0, the system operates in auto-commit mode.
 *
 * Snapshot isolation: every transaction captures the commit sequence at
 * BEGIN; a reader only sees versions committed at or before its snapshot,
 * so concurrent commits never change an open transaction's view
 * (repeatable reads). Auto-commit statements read the latest committed
 * state.
 */
@Component
public class TransactionManager {

    private final AtomicLong txnCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, String> activeTxns = new ConcurrentHashMap<>(); // txnId -> status
    private final ConcurrentHashMap<String, Long> sessionTxns = new ConcurrentHashMap<>(); // sessionId -> txnId
    private final AtomicLong commitSeqCounter = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Long> beginSnapshots = new ConcurrentHashMap<>(); // txnId -> snapshot seq
    private final ConcurrentHashMap<Long, Long> txnCommitSeq = new ConcurrentHashMap<>(); // txnId -> commit seq

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
        // Snapshot = last committed sequence: anything committing later
        // gets a strictly greater number and stays invisible.
        beginSnapshots.put(txnId, Math.max(0, commitSeqCounter.get() - 1));
        return txnId;
    }

    /**
     * Commit a transaction for the given session.
     */
    public long commit(String sessionId) {
        Long txnId = sessionTxns.remove(sessionId);
        if (txnId == null) throw new IllegalStateException("No active transaction for this session");
        activeTxns.put(txnId, "COMMITTED");
        txnCommitSeq.put(txnId, commitSeqCounter.getAndIncrement());
        beginSnapshots.remove(txnId);
        return txnId;
    }

    /**
     * Rollback a transaction for the given session.
     */
    public long rollback(String sessionId) {
        Long txnId = sessionTxns.remove(sessionId);
        if (txnId == null) throw new IllegalStateException("No active transaction for this session");
        activeTxns.put(txnId, "ROLLED_BACK");
        beginSnapshots.remove(txnId);
        return txnId;
    }

    /**
     * Commit sequence assigned at commit time; -1 if never committed.
     * The executor stamps row versions with this after a successful commit.
     */
    public long getCommitSeq(long txnId) {
        return txnCommitSeq.getOrDefault(txnId, -1L);
    }

    /**
     * Snapshot sequence captured at BEGIN; defaults to the latest commit
     * for unknown readers.
     */
    public long getSnapshotSeq(long txnId) {
        return beginSnapshots.getOrDefault(txnId, Math.max(0, commitSeqCounter.get() - 1));
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
     * 1. It was created by the reader's own transaction (and not deleted by it)
     * 2. It was created by a transaction committed at or before the
     *    reader's snapshot — never by an active or rolled-back one
     * 3. It was NOT deleted by a transaction committed at or before the snapshot
     *
     * <p>Rule 2 is repeatable reads: concurrent commits never move an
     * open transaction's snapshot.
     */
    public boolean isVisible(RowVersion rv, long readerTxnId) {
        return isVisible(rv, readerTxnId, getSnapshotSeq(readerTxnId));
    }

    public boolean isVisible(RowVersion rv, long readerTxnId, long snapshotSeq) {
        // Own writes are visible — except rows this reader deleted itself.
        if (rv.createdByTxn == readerTxnId) {
            return rv.deletedByTxn == 0;
        }

        // Row created by a rolled-back transaction — never visible
        if ("ROLLED_BACK".equals(activeTxns.get(rv.createdByTxn))) {
            return false;
        }

        // Row created by an active (uncommitted) transaction — not visible to others
        if ("ACTIVE".equals(activeTxns.get(rv.createdByTxn))) {
            return false;
        }

        // Committed creator: visible only if committed within the snapshot.
        if (!committedAtOrBefore(rv.createdByTxn, rv.commitSeq, snapshotSeq)) {
            return false;
        }
        if (!rv.deleted) {
            return true;
        }

        // Deleted by this reader's own txn — not visible
        if (rv.deletedByTxn == readerTxnId) return false;

        // Deleted by a rolled-back txn — deletion doesn't count, row is visible
        String deleterStatus = activeTxns.get(rv.deletedByTxn);
        if ("ROLLED_BACK".equals(deleterStatus)) return true;

        // Deleted by an active txn — deletion not yet committed, row still visible
        if ("ACTIVE".equals(deleterStatus)) return true;

        // Deleted by a committed txn — gone only if the delete is within the snapshot.
        return !committedAtOrBefore(rv.deletedByTxn, rv.deletedCommitSeq, snapshotSeq);
    }

    /**
     * True when a -1 stamp means "committed long ago" (data that predates
     * stamping or bypassed it): fall back to status so legacy rows stay
     * visible instead of vanishing.
     */
    private boolean committedAtOrBefore(long txnId, long seq, long snapshotSeq) {
        if (seq != -1) return seq <= snapshotSeq;
        String status = activeTxns.get(txnId);
        return !"ACTIVE".equals(status) && !"ROLLED_BACK".equals(status);
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
