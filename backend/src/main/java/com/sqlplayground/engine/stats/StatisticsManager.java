package com.sqlplayground.engine.stats;

import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StatisticsManager {

    private final ConcurrentHashMap<String, TableStats> statsCache = new ConcurrentHashMap<>();

    public void analyze(String tableName, Table table) {
        TableStats stats = TableStats.compute(tableName, table);
        statsCache.put(tableName.toLowerCase(), stats);
    }

    public Optional<TableStats> getStats(String tableName) {
        return Optional.ofNullable(statsCache.get(tableName.toLowerCase()));
    }

    public double estimateSelectivity(String tableName, String columnName, String operator, Object literal) {
        Optional<TableStats> stats = getStats(tableName);
        if (stats.isEmpty()) return 0.3;

        ColumnStats colStats = stats.get().getColumn(columnName);
        if (colStats == null) return 0.3;

        return colStats.estimateSelectivity(operator, literal);
    }

    public void analyzeAll(InMemoryDatabase db) {
        for (Map.Entry<String, Table> entry : db.getAllTables().entrySet()) {
            analyze(entry.getKey(), entry.getValue());
        }
    }

    public Map<String, Object> getAllStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, TableStats> entry : statsCache.entrySet()) {
            TableStats ts = entry.getValue();
            Map<String, Object> tableStats = new LinkedHashMap<>();
            tableStats.put("rowCount", ts.rowCount);
            Map<String, Object> colStats = new LinkedHashMap<>();
            for (Map.Entry<String, ColumnStats> colEntry : ts.columns.entrySet()) {
                ColumnStats cs = colEntry.getValue();
                Map<String, Object> csMap = new LinkedHashMap<>();
                csMap.put("rowCount", cs.rowCount);
                csMap.put("ndv", cs.ndv);
                csMap.put("minValue", cs.minValue);
                csMap.put("maxValue", cs.maxValue);
                csMap.put("histogramBuckets", cs.histogramBuckets);
                colStats.put(colEntry.getKey(), csMap);
            }
            tableStats.put("columns", colStats);
            result.put(entry.getKey(), tableStats);
        }
        return result;
    }

    public void clear() {
        statsCache.clear();
    }
}
