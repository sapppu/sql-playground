package com.sqlplayground.engine;

import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.planner.QueryPlanner;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.model.Table;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #4: join strategy selection — HASH_JOIN at/above 200 build-side rows,
 * NESTED_LOOP_JOIN below; node names carry strategy, type rides in stats.
 */
class JoinStrategyTest extends EngineTestBase {

    private QueryPlanner planner;
    private StatisticsManager stats;

    private void makeBig(String name, int n) {
        q("CREATE TABLE " + name + " (id INTEGER PRIMARY KEY, v INTEGER)");
        Table t = db.getTable(name);
        for (int i = 1; i <= n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", (long) i);
            row.put("v", (long) i * 10);
            t.insertRow(row);
        }
    }

    private QueryPlanner.PlanNode plan(String sql) {
        stats = new StatisticsManager();
        stats.analyzeAll(db);
        planner = new QueryPlanner(db, new IndexManager(), stats);
        return planner.plan(new Parser(new Lexer(sql).tokenize()).parse());
    }

    private static QueryPlanner.PlanNode findJoin(QueryPlanner.PlanNode node) {
        if (node.getOperation().endsWith("_JOIN")) return node;
        for (QueryPlanner.PlanNode child : node.getChildren()) {
            QueryPlanner.PlanNode hit = findJoin(child);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    void smallJoinUsesNestedLoop() {
        QueryPlanner.PlanNode join = findJoin(plan(
            "SELECT * FROM employees JOIN departments ON employees.department = departments.name"));
        assertNotNull(join);
        assertEquals("NESTED_LOOP_JOIN", join.getOperation());
        assertEquals("nested_loop", join.getStats().get("strategy"));
        assertEquals("INNER", join.getStats().get("join_type"));
        assertEquals("employees", join.getStats().get("left_table"));
        assertEquals("departments", join.getStats().get("right_table"));
        assertNotNull(join.getStats().get("estimated_rows"));
        // Build side is the smaller input: departments (3 rows) builds here
        assertEquals("departments", join.getStats().get("build_table"));
        assertEquals("employees", join.getStats().get("probe_table"));
    }

    @Test
    void bigJoinUsesHashJoinAndReturnsCorrectRows() {
        makeBig("big1", 210);
        makeBig("big2", 210);
        QueryPlanner.PlanNode join = findJoin(plan(
            "SELECT * FROM big1 JOIN big2 ON big1.id = big2.id"));
        assertNotNull(join);
        assertEquals("HASH_JOIN", join.getOperation());
        assertEquals("hash_join", join.getStats().get("strategy"));

        // Executor must honor the same rule and still return correct results
        QueryResult r = q("SELECT * FROM big1 JOIN big2 ON big1.id = big2.id");
        assertEquals(210, r.rows().size());
    }

    @Test
    void thresholdBoundary() {
        assertEquals("nested_loop", QueryPlanner.chooseStrategy(199, 500));
        assertEquals("hash_join", QueryPlanner.chooseStrategy(200, 500));
        assertEquals("hash_join", QueryPlanner.chooseStrategy(1000, 200));
    }
}
