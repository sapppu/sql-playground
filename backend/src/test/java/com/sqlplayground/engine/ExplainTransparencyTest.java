package com.sqlplayground.engine;

import com.sqlplayground.engine.executor.QueryExecutor;
import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.planner.PlanAnnotator;
import com.sqlplayground.engine.planner.QueryPlanner;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.storage.InMemoryDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Section 1: optimizer transparency — every plan node carries
 * estimatedRows, execution stamps actualRows, and stale statistics
 * visibly move the estimates after ANALYZE.
 */
class ExplainTransparencyTest {

    private static final String JOIN_SQL =
        "SELECT * FROM employees JOIN departments ON employees.department = departments.name";

    private InMemoryDatabase db;
    private StatisticsManager stats;
    private QueryPlanner planner;
    private QueryExecutor executor;

    private static AstNode parse(String sql) {
        return new Parser(new Lexer(sql).tokenize()).parse();
    }

    private QueryPlanner.PlanNode plan(String sql) {
        return planner.plan(parse(sql));
    }

    private QueryResult exec(String sql) {
        return executor.execute(parse(sql));
    }

    private QueryPlanner.PlanNode planAndAnnotate(String sql) {
        QueryPlanner.PlanNode node = plan(sql);
        exec(sql);
        PlanAnnotator.annotate(node, executor.getAndClearLastProfile());
        return node;
    }

    private static QueryPlanner.PlanNode findOp(QueryPlanner.PlanNode node, String op) {
        if (node.getOperation().equals(op)) return node;
        for (QueryPlanner.PlanNode child : node.getChildren()) {
            QueryPlanner.PlanNode hit = findOp(child, op);
            if (hit != null) return hit;
        }
        return null;
    }

    private static QueryPlanner.PlanNode findJoin(QueryPlanner.PlanNode node) {
        if (node.getOperation().endsWith("_JOIN")) return node;
        for (QueryPlanner.PlanNode child : node.getChildren()) {
            QueryPlanner.PlanNode hit = findJoin(child);
            if (hit != null) return hit;
        }
        return null;
    }

    @BeforeEach
    void setUp() {
        db = new InMemoryDatabase();
        db.seedSampleData();
        stats = new StatisticsManager();
        stats.analyzeAll(db);
        planner = new QueryPlanner(db, new IndexManager(), stats);
        executor = new QueryExecutor(db, new WriteAheadLog(), new IndexManager(),
            new TransactionManager(), stats);
    }

    @Test
    void joinNodeCarriesEstimateAndActual() {
        QueryPlanner.PlanNode join = findJoin(planAndAnnotate(JOIN_SQL));
        assertNotNull(join);
        assertNotNull(join.getStats().get("estimatedRows"), "join must carry estimatedRows");
        assertEquals(8, join.getActualRows(), "all 8 seeded employees match a department");
    }

    @Test
    void scanAndFilterNodesAnnotated() {
        QueryPlanner.PlanNode root =
            planAndAnnotate("SELECT name FROM employees WHERE salary > 100000");
        QueryPlanner.PlanNode scan = findOp(root, "SEQ_SCAN");
        assertNotNull(scan);
        assertEquals(8, scan.getStats().get("estimatedRows"));
        assertEquals(8, scan.getActualRows());
        QueryPlanner.PlanNode filter = findOp(root, "FILTER");
        assertNotNull(filter);
        assertNotNull(filter.getStats().get("estimatedRows"));
        assertNotNull(filter.getStats().get("selectivity"));
        assertEquals(2, filter.getActualRows(), "Carol and Henry earn over 100000");
    }

    @Test
    void joinSummaryNamesStrategyBuildProbeAndThreshold() {
        QueryPlanner.PlanNode join = findJoin(plan(JOIN_SQL));
        String summary = String.valueOf(join.getStats().get("summary"));
        assertTrue(summary.contains("NESTED_LOOP_JOIN"), summary);
        assertTrue(summary.contains("departments"), summary);
        assertTrue(summary.contains("employees"), summary);
        assertTrue(summary.contains("200"), summary);
    }

    @Test
    void staleEstimatesMoveAfterAnalyze() {
        // Baseline with fresh stats: build input is departments (3 rows).
        long freshBuildEst = ((Number) findJoin(plan(JOIN_SQL)).getStats().get("estimated_rows")).longValue();
        assertEquals(3, freshBuildEst);

        // Bulk-load 300 departments WITHOUT re-analyzing: the stats-derived
        // build estimate goes stale while nothing else changes.
        for (int i = 0; i < 300; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "BulkDept" + i);
            row.put("budget", 1000.0);
            row.put("location", "Floor 9");
            db.getTable("departments").insertRow(row);
        }

        QueryPlanner.PlanNode staleJoin = findJoin(plan(JOIN_SQL));
        long staleBuildEst = ((Number) staleJoin.getStats().get("estimated_rows")).longValue();
        assertEquals(freshBuildEst, staleBuildEst, "estimate must not move while stats are stale");
        assertEquals(8, findJoin(planAndAnnotate(JOIN_SQL)).getActualRows(),
            "actuals track reality even when estimates do not");
        assertTrue(String.valueOf(staleJoin.getStats().get("summary")).contains("~3 rows"),
            "stale summary still describes the old build size");

        stats.analyzeAll(db);
        QueryPlanner.PlanNode freshJoin = findJoin(plan(JOIN_SQL));
        long newBuildEst = ((Number) freshJoin.getStats().get("estimated_rows")).longValue();
        assertEquals(8, newBuildEst, "fresh build estimate follows the smaller input");
        assertTrue(String.valueOf(freshJoin.getStats().get("summary")).contains("~303 rows"),
            "fresh summary describes the grown probe side");
        assertEquals(8, findJoin(planAndAnnotate(JOIN_SQL)).getActualRows());
    }
}
