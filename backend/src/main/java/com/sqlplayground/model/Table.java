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
            return Collections.unmodifiableList(rows);
        }
        // MVCC: filter through version chains
        List<Map<String, Object>> visible = new ArrayList<>();
        for (List<RowVersion> chain : versionChains) {
            for (RowVersion rv : chain) {
                if (txnManager.isVisible(rv, readerTxnId) && !rv.deleted) {
                    visible.add(rv.data);
                    break;
                }
            }
        }
        return visible;
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
            int before = rows.size();
            rows.removeIf(predicate);
            return before - rows.size();
        }
        // MVCC: mark matching versions as deleted
        int count = 0;
        for (List<RowVersion> chain : versionChains) {
            for (RowVersion rv : chain) {
                if (predicate.test(rv.data) && rv.deletedByTxn == 0) {
                    rv.deleted = true;
                    rv.deletedByTxn = txnId;
                    count++;
                    break;
                }
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
        if (txnId == 0 || txnManager == null) {
            int count = 0;
            for (Map<String, Object> row : rows) {
                if (predicate.test(row)) {
                    updater.accept(row);
                    count++;
                }
            }
            return count;
        }
        // MVCC: create new version for each matched row, mark old as deleted
        int count = 0;
        for (List<RowVersion> chain : versionChains) {
            for (int i = 0; i < chain.size(); i++) {
                RowVersion rv = chain.get(i);
                if (predicate.test(rv.data) && rv.deletedByTxn == 0) {
                    Map<String, Object> newData = new LinkedHashMap<>(rv.data);
                    updater.accept(newData);
                    rv.deleted = true;
                    rv.deletedByTxn = txnId;
                    RowVersion newRv = new RowVersion(txnId, newData);
                    chain.add(newRv);
                    count++;
                    break;
                }
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
