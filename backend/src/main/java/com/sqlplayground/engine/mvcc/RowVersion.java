package com.sqlplayground.engine.mvcc;

import java.util.Map;

/**
 * Represents a versioned snapshot of a row for MVCC.
 * Each row version tracks which transaction created and deleted it,
 * plus the commit sequence numbers that snapshot isolation reads use.
 * A commitSeq of -1 means "no committed state yet" (created or deleted
 * by a still-active transaction).
 */
public class RowVersion {
    public final long txnId;
    public final Map<String, Object> data;
    public boolean deleted;
    public final long createdByTxn;
    public long deletedByTxn; // 0 = not deleted
    public long commitSeq; // commit order of the creating txn, -1 if uncommitted
    public long deletedCommitSeq; // commit order of the deleting txn, -1 if uncommitted

    public RowVersion(long txnId, Map<String, Object> data) {
        this.txnId = txnId;
        this.data = data;
        this.deleted = false;
        this.createdByTxn = txnId;
        this.deletedByTxn = 0;
        this.commitSeq = -1;
        this.deletedCommitSeq = -1;
    }
}
