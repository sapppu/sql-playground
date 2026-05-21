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
            // Auto-commit mode — include plain rows (auto-commit inserts) +
            // latest non-deleted committed version from each chain
            List<Map<String, Object>> result = new ArrayList<>(rows);
            for (List<RowVersion> chain : versionChains) {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    RowVersion rv = chain.get(i);
                    if (txnManager != null && txnManager.isRolledBack(rv.createdByTxn)) continue;
                    if (!rv.deleted) { result.add(rv.data); break; }
                }
            }
            return Collections.unmodifiableList(result);
        }

        // MVCC read — use visibility rules
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<RowVersion> chain : versionChains) {
            for (int i = chain.size() - 1; i >= 0; i--) {
                RowVersion rv = chain.get(i);
                if (txnManager.isVisible(rv, readerTxnId)) {
                    result.add(rv.data);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
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

        // MVCC: logical delete — mark deletedByTxn instead of removing
        int count = 0;
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
