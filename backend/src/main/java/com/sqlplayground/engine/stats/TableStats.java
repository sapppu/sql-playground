package com.sqlplayground.engine.stats;

import com.sqlplayground.model.Table;

import java.util.*;
import java.util.stream.Collectors;

public class TableStats {

    public final String tableName;
    public final long rowCount;
    public final Map<String, ColumnStats> columns;

    private TableStats(String tableName, long rowCount, Map<String, ColumnStats> columns) {
        this.tableName = tableName;
        this.rowCount = rowCount;
        this.columns = columns;
    }

    public static TableStats compute(String tableName, Table table) {
        List<Map<String, Object>> rows = table.getRows();
        Map<String, ColumnStats> colStats = new LinkedHashMap<>();

        for (Table.Column col : table.columns) {
            colStats.put(col.getName().toLowerCase(),
                ColumnStats.compute(col.getName(), rows));
        }

        return new TableStats(tableName.toLowerCase(), rows.size(), colStats);
    }

    public ColumnStats getColumn(String columnName) {
        return columns.get(columnName.toLowerCase());
    }
}
