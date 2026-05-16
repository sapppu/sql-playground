package com.sqlplayground.engine.mvcc;

import java.util.Map;

/**
 * Represents a versioned snapshot of a row for MVCC.
 * Each row version tracks which transaction created and deleted it.
 */
public class RowVersion {
    public final long txnId;
    public final Map<String, Object> data;
    public boolean deleted;
    public final long createdByTxn;
    public long deletedByTxn; // 0 = not deleted

    public RowVersion(long txnId, Map<String, Object> data) {
        this.txnId = txnId;
        this.data = data;
        this.deleted = false;
        this.createdByTxn = txnId;
        this.deletedByTxn = 0;
    }
}
