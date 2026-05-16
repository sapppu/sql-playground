package com.sqlplayground.engine.index;

import com.sqlplayground.model.Table;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages B-Tree indexes. Indexes are stored keyed by "tableName.columnName" (lowercase).
 * When a table is mutated (INSERT/DELETE/UPDATE), all its indexes are invalidated.
 */
@Component
public class IndexManager {

    private final Map<String, BTreeIndex> indexes = new ConcurrentHashMap<>();
    // Track index name → table.column mapping for DROP INDEX by name
    private final Map<String, String> indexNameToKey = new ConcurrentHashMap<>();

    private String makeKey(String tableName, String columnName) {
        return tableName.toLowerCase() + "." + columnName.toLowerCase();
    }

    /**
     * Create a new B-Tree index on a table column, populating it with existing rows.
     */
    @SuppressWarnings("unchecked")
    public String createIndex(String indexName, String tableName, String columnName, Table table) {
        String key = makeKey(tableName, columnName);
        if (indexes.containsKey(key)) {
            throw new IllegalStateException("Index already exists on " + tableName + "." + columnName);
        }

        BTreeIndex index = new BTreeIndex(columnName);

        // Populate with existing rows
        for (Map<String, Object> row : table.getRows()) {
            Object val = row.get(columnName);
            if (val == null) val = row.get(columnName.toLowerCase());
            if (val instanceof Comparable) {
                index.insert((Comparable) val, row);
            }
        }

        indexes.put(key, index);
        indexNameToKey.put(indexName.toLowerCase(), key);
        return key;
    }

    /**
     * Get the index for a given table and column, if one exists.
     */
    public Optional<BTreeIndex> getIndex(String tableName, String columnName) {
        return Optional.ofNullable(indexes.get(makeKey(tableName, columnName)));
    }

    /**
     * Check if an index exists for a given table and column.
     */
    public boolean indexExists(String tableName, String columnName) {
        return indexes.containsKey(makeKey(tableName, columnName));
    }

    /**
     * Remove all indexes for a table (called after INSERT/DELETE/UPDATE).
     */
    public void invalidate(String tableName) {
        String prefix = tableName.toLowerCase() + ".";
        indexes.keySet().removeIf(k -> k.startsWith(prefix));
        indexNameToKey.values().removeIf(v -> v.startsWith(prefix));
    }

    /**
     * Drop an index by its name.
     */
    public void dropIndex(String indexName) {
        String key = indexNameToKey.remove(indexName.toLowerCase());
        if (key != null) {
            indexes.remove(key);
        } else {
            throw new IllegalStateException("Index not found: " + indexName);
        }
    }

    /**
     * Returns all active index keys (e.g., ["employees.salary", "products.price"]).
     */
    public Set<String> getIndexKeys() {
        return Collections.unmodifiableSet(indexes.keySet());
    }

    /**
     * Returns named index mapping for display.
     */
    public Map<String, String> getNamedIndexes() {
        return Collections.unmodifiableMap(indexNameToKey);
    }
}
