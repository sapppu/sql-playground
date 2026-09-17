package com.sqlplayground.engine;

import com.sqlplayground.engine.index.BTreeIndex;
import com.sqlplayground.engine.stats.ColumnStats;
import com.sqlplayground.engine.util.Values;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #1: range comparisons across mixed boxed numeric types
 * (Double column vs Long literal and vice versa) must not throw
 * ClassCastException — at any layer.
 */
class RangeComparisonTest extends EngineTestBase {

    @Test
    void valuesCompareHandlesMixedNumerics() {
        assertTrue(Values.compare(80000L, 95000.0) < 0);
        assertTrue(Values.compare(95000.0, 80000L) > 0);
        assertEquals(0, Values.compare(5L, 5.0));
        assertEquals(0, Values.compare(5, 5L));
        assertTrue(Values.equalValues(5L, 5.0));
    }

    @Test
    void rangeOperatorsOnDoubleColumnWithLongLiteral() {
        assertFalse(q("SELECT name FROM employees WHERE salary > 80000").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE salary >= 80000").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE salary < 80000").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE salary <= 80000").rows().isEmpty());
    }

    @Test
    void rangeOperatorsWithDoubleLiteral() {
        assertFalse(q("SELECT name FROM employees WHERE salary > 80000.0").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE salary >= 50000.0").rows().isEmpty());
    }

    @Test
    void rangeOperatorsOnLongColumnWithLongLiteral() {
        assertFalse(q("SELECT name FROM employees WHERE id < 5").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE id >= 1").rows().isEmpty());
    }

    @Test
    void rangeWithIndexPresent() {
        q("CREATE INDEX idx_salary ON employees (salary)");
        // Must not throw even though the index holds Doubles and bounds are mixed
        assertFalse(q("SELECT name FROM employees WHERE salary > 80000").rows().isEmpty());
        assertFalse(q("SELECT name FROM employees WHERE salary < 80000").rows().isEmpty());
    }

    @Test
    void btreeMixedNumericKeys() {
        BTreeIndex idx = new BTreeIndex("salary");
        Map<String, Object> r1 = Map.of("salary", 95000.0);
        Map<String, Object> r2 = Map.of("salary", 72000L);
        idx.insert((Comparable) r1.get("salary"), r1);
        idx.insert((Comparable) r2.get("salary"), r2);
        assertEquals(1, idx.search(95000L).size());
        assertEquals(2, idx.rangeSearch(0.0, 100000L).size());
    }

    @Test
    void statsSelectivityWithMixedLiteralTypes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("salary", 95000.0));
        rows.add(Map.of("salary", 72000.0));
        ColumnStats stats = ColumnStats.compute("salary", rows);
        assertDoesNotThrow(() -> stats.estimateSelectivity(">", 80000L));
        assertDoesNotThrow(() -> stats.estimateSelectivity("<=", 80000.0));
    }
}
