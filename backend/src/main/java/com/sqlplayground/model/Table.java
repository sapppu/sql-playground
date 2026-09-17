package com.sqlplayground.model;

import com.sqlplayground.engine.mvcc.RowVersion;
import com.sqlplayground.engine.mvcc.TransactionManager;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Table {

    public final String name;
    public final List<Column> columns;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<List<RowVersion>> versionChains = new ArrayList<>();
    private long pkCounter = 1;
    private TransactionManager txnManager;

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
    }

    public void setTransactionManager(TransactionManager txnManager) {
        this.txnManager = txnManager;
    }

    public TransactionManager getTransactionManager() {
        return txnManager;
    }

    public List<Map<String, Object>> getRows() {
        return getRows(0);
    }

    public List<Map<String, Object>> getRows(long readerTxnId) {
        if (readerTxnId == 0 || txnManager == null) {
            // Auto-commit mode: latest committed state. Uncommitted inserts
            // from open transactions are NOT visible here (no dirty reads),
            // while uncommitted deletes don't hide rows yet.
            List<Map<String, Object>> result = new ArrayList<>(rows);
            for (List<RowVersion> chain : versionChains) {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    RowVersion rv = chain.get(i);
                    if (txnManager != null && txnManager.isRolledBack(rv.createdByTxn)) continue;
                    if (txnManager != null && txnManager.isActive(rv.createdByTxn)) continue;
                    if (!rv.deleted) { result.add(rv.data); break; }
                    if (txnManager == null
                            || txnManager.isRolledBack(rv.deletedByTxn)
                            || txnManager.isActive(rv.deletedByTxn)) {
                        result.add(rv.data);
                    }
                    break;
                }
            }
            return Collections.unmodifiableList(result);
        }

        // MVCC read — snapshot isolation via the reader's begin snapshot.
        // Plain rows are auto-commit-era data: committed by definition, so
        // they predate every snapshot and are visible as the baseline.
        // (Auto-commit writes concurrent with an open txn are visible
        // immediately — snapshot gating covers transaction-committed data.
        // See class-level docs in TransactionManager.)
        long snapshot = txnManager.getSnapshotSeq(readerTxnId);
        List<Map<String, Object>> result = new ArrayList<>(rows);
        for (List<RowVersion> chain : versionChains) {
            for (int i = chain.size() - 1; i >= 0; i--) {
                RowVersion rv = chain.get(i);
                if (txnManager.isVisible(rv, readerTxnId, snapshot)) {
                    result.add(rv.data);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Stamp every version written by {@code txnId} with its commit sequence.
     * Called once by the executor right after a successful COMMIT so later
     * snapshots can order the transaction's writes.
     */
    public void stampCommit(long txnId, long commitSeq) {
        for (List<RowVersion> chain : versionChains) {
            for (RowVersion rv : chain) {
                if (rv.createdByTxn == txnId && rv.commitSeq == -1) {
                    rv.commitSeq = commitSeq;
                }
                if (rv.deletedByTxn == txnId && rv.deletedCommitSeq == -1) {
                    rv.deletedCommitSeq = commitSeq;
                }
            }
        }
    }

    public void insertRow(Map<String, Object> row) {
        insertRow(row, 0);
    }

    public void insertRow(Map<String, Object> row, long txnId) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Column col : columns) {
            Object val = row.get(col.getName());
            if (val == null && col.isPrimaryKey()) val = pkCounter++;
            normalized.put(col.getName(), val);
        }
        validateNotNull(normalized);
        if (txnId != 0 && txnManager != null) {
            RowVersion rv = new RowVersion(txnId, normalized);
            List<RowVersion> chain = new ArrayList<>();
            chain.add(rv);
            versionChains.add(chain);
        } else {
            rows.add(normalized);
        }
    }

    public int deleteRows(Predicate<Map<String, Object>> predicate) {
        return deleteRows(predicate, 0);
    }

    public int deleteRows(Predicate<Map<String, Object>> predicate, long txnId) {
        if (txnId == 0 || txnManager == null) {
            // Auto-commit: remove from both rows and versionChains
            int count1 = 0;
            Iterator<Map<String, Object>> it = rows.iterator();
            while (it.hasNext()) {
                if (predicate.test(it.next())) {
                    it.remove();
                    count1++;
                }
            }
            int before = versionChains.size();
            versionChains.removeIf(chain -> {
                RowVersion latest = chain.get(chain.size() - 1);
                return !latest.deleted && predicate.test(latest.data);
            });
            return count1 + (before - versionChains.size());
        }

        // MVCC: logical delete — mark deletedByTxn instead of removing.
        // Plain (auto-commit-era) rows get tombstone versions so open
        // snapshots keep seeing them; without this, deleting seed data
        // inside a transaction would silently affect zero rows.
        int count = 0;
        Iterator<Map<String, Object>> it = rows.iterator();
        while (it.hasNext()) {
            Map<String, Object> row = it.next();
            if (predicate.test(row)) {
                RowVersion tombstone = new RowVersion(0, new LinkedHashMap<>(row));
                tombstone.deleted = true;
                tombstone.deletedByTxn = txnId;
                List<RowVersion> chain = new ArrayList<>();
                chain.add(tombstone);
                versionChains.add(chain);
                it.remove();
                count++;
            }
        }
        for (List<RowVersion> chain : versionChains) {
            RowVersion latest = chain.get(chain.size() - 1);
            if (!latest.deleted && txnManager.isVisible(latest, txnId)
                    && predicate.test(latest.data)) {
                latest.deleted = true;
                latest.deletedByTxn = txnId;
                count++;
            }
        }
        return count;
    }

    public int updateRows(Predicate<Map<String, Object>> predicate,
                          Consumer<Map<String, Object>> updater) {
        return updateRows(predicate, updater, 0);
    }

    public int updateRows(Predicate<Map<String, Object>> predicate,
                          Consumer<Map<String, Object>> updater,
                          long txnId) {
        // MVCC: callers inside a transaction get copy-on-write versions so
        // uncommitted updates never mutate state other readers can see.
        if (txnId != 0 && txnManager != null) {
            int count = 0;
            Iterator<Map<String, Object>> it = rows.iterator();
            while (it.hasNext()) {
                Map<String, Object> row = it.next();
                if (predicate.test(row)) {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    updater.accept(copy);
                    validateNotNull(copy);
                    List<RowVersion> chain = new ArrayList<>();
                    chain.add(new RowVersion(txnId, copy));
                    versionChains.add(chain);
                    it.remove();
                    count++;
                }
            }
            for (List<RowVersion> chain : versionChains) {
                RowVersion latest = chain.get(chain.size() - 1);
                if (!latest.deleted && txnManager.isVisible(latest, txnId)
                        && predicate.test(latest.data)) {
                    Map<String, Object> copy = new LinkedHashMap<>(latest.data);
                    updater.accept(copy);
                    validateNotNull(copy);
                    chain.add(new RowVersion(txnId, copy));
                    count++;
                }
            }
            return count;
        }
        int count = 0;
        // Also update plain rows (auto-commit data)
        for (Map<String, Object> row : rows) {
            if (predicate.test(row)) {
                updater.accept(row);
                count++;
            }
        }
        // Update version chains
        for (List<RowVersion> chain : versionChains) {
            RowVersion latest = chain.get(chain.size() - 1);
            if (!latest.deleted && predicate.test(latest.data)) {
                updater.accept(latest.data);
                count++;
            }
        }
        return count;
    }

    public void clearRows() {
        rows.clear();
        versionChains.clear();
        pkCounter = 1;
    }

    /** Reject rows that violate a NOT NULL column constraint. */
    public void validateNotNull(Map<String, Object> row) {
        for (Column col : columns) {
            if (col.isNotNull() && row.get(col.getName()) == null) {
                throw new IllegalArgumentException(
                    "Column '" + col.getName() + "' cannot be null (NOT NULL constraint on table '" + name + "')");
            }
        }
    }

    public static class Column {
        private final String name;
        private final String type;
        private final boolean primaryKey;
        private final boolean notNull;

        public Column(String name, String type, boolean primaryKey, boolean notNull) {
            this.name = name;
            this.type = type;
            this.primaryKey = primaryKey;
            this.notNull = notNull;
        }

        public String getName()       { return name; }
        public String getType()       { return type; }
        public boolean isPrimaryKey() { return primaryKey; }
        public boolean isNotNull()    { return notNull; }
    }
}
