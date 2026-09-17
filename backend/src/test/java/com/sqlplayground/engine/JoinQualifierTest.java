package com.sqlplayground.engine;

import com.sqlplayground.engine.executor.QueryResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #3: qualified column references (table.column) in JOIN conditions
 * must resolve against the qualified key — never against a colliding
 * unqualified left-table value.
 */
class JoinQualifierTest extends EngineTestBase {

    @Test
    void innerJoinReturnsMatchingRows() {
        QueryResult r = q("SELECT employees.name, departments.budget FROM employees " +
            "JOIN departments ON employees.department = departments.name");
        assertFalse(r.rows().isEmpty(), "INNER JOIN should match seeded departments");
        for (var row : r.rows()) {
            // explicit select lists project under unqualified labels
            assertNotNull(row.get("budget"));
        }
    }

    @Test
    void leftJoinReturnsMatchedPlusNullPadded() {
        QueryResult all = q("SELECT * FROM employees LEFT JOIN departments " +
            "ON employees.department = departments.name");
        QueryResult inner = q("SELECT * FROM employees JOIN departments " +
            "ON employees.department = departments.name");
        assertFalse(inner.rows().isEmpty());
        assertTrue(all.rows().size() >= inner.rows().size(),
            "LEFT JOIN must include every INNER row plus unmatched ones");
        long matched = all.rows().stream()
            .filter(row -> row.get("departments.name") != null)
            .count();
        assertEquals(inner.rows().size(), matched);
    }

    @Test
    void qualifiedFilterOnLeftTableWithJoin() {
        QueryResult r = q("SELECT employees.name FROM employees " +
            "JOIN departments ON employees.department = departments.name " +
            "WHERE employees.salary > 80000");
        assertFalse(r.rows().isEmpty());
    }
}
