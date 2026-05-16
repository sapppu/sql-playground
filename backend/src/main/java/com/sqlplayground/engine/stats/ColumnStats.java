package com.sqlplayground.engine.stats;

import java.util.*;
import java.util.stream.Collectors;

public class ColumnStats {

    public final String columnName;
    public final long rowCount;
    public final long ndv;
    public final Object minValue;
    public final Object maxValue;
    public final List<Object> histogramBuckets;

    private ColumnStats(String columnName, long rowCount, long ndv,
                        Object minValue, Object maxValue,
                        List<Object> histogramBuckets) {
        this.columnName = columnName;
        this.rowCount = rowCount;
        this.ndv = ndv;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.histogramBuckets = histogramBuckets;
    }

    @SuppressWarnings("unchecked")
    public static ColumnStats compute(String columnName, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new ColumnStats(columnName, 0, 0, null, null, Collections.emptyList());
        }

        Set<Object> distinctValues = new HashSet<>();
        List<Comparable> sortedValues = new ArrayList<>();
        Object min = null;
        Object max = null;

        for (Map<String, Object> row : rows) {
            Object val = row.get(columnName);
            if (val == null) continue;

            distinctValues.add(val);
            if (val instanceof Comparable) {
                sortedValues.add((Comparable) val);
                if (min == null || ((Comparable) val).compareTo(min) < 0) min = val;
                if (max == null || ((Comparable) val).compareTo(max) > 0) max = val;
            }
        }

        long ndv = distinctValues.size();
        Collections.sort(sortedValues);

        // Pick 10 evenly-spaced samples for histogram
        List<Object> buckets = new ArrayList<>();
        if (!sortedValues.isEmpty()) {
            int step = Math.max(1, sortedValues.size() / 10);
            for (int i = 0; i < sortedValues.size(); i += step) {
                if (buckets.size() < 10) buckets.add(sortedValues.get(i));
            }
            if (buckets.isEmpty() || !buckets.get(buckets.size() - 1).equals(sortedValues.get(sortedValues.size() - 1))) {
                if (buckets.size() < 10) buckets.add(sortedValues.get(sortedValues.size() - 1));
            }
        }

        return new ColumnStats(columnName, rows.size(), Math.max(1, ndv), min, max, buckets);
    }

    public double estimateSelectivity(String operator, Object literal) {
        if (rowCount == 0) return 0.3;

        switch (operator.toUpperCase()) {
            case "=":
            case "==":
                return 1.0 / ndv;
            case "<":
            case "<=":
                if (minValue instanceof Comparable && literal instanceof Comparable) {
                    int cmp = ((Comparable) literal).compareTo(minValue);
                    if (cmp < 0) return 0.01;
                    if (maxValue instanceof Comparable) {
                        int cmp2 = ((Comparable) literal).compareTo(maxValue);
                        if (cmp2 >= 0) return 0.99;
                    }
                    return 0.3;
                }
                return 0.3;
            case ">":
            case ">=":
                return 1.0 - estimateSelectivity("<=", literal);
            case "!=":
            case "<>":
                return 1.0 - (1.0 / ndv);
            case "LIKE":
                return 0.1;
            default:
                return 0.3;
        }
    }
}
