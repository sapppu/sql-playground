package com.sqlplayground.api;

import com.sqlplayground.engine.executor.QueryExecutor;
import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.lexer.Token;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.planner.QueryPlanner;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WalEntry;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SqlController {

    private final InMemoryDatabase db;
    private final QueryExecutor executor;
    private final QueryPlanner planner;
    private final WriteAheadLog wal;
    private final IndexManager indexManager;
    private final TransactionManager txnManager;
    private final StatisticsManager statisticsManager;

    public SqlController(InMemoryDatabase db, QueryExecutor executor,
                         QueryPlanner planner, WriteAheadLog wal,
                         IndexManager indexManager, TransactionManager txnManager,
                         StatisticsManager statisticsManager) {
        this.db = db;
        this.executor = executor;
        this.planner = planner;
        this.wal = wal;
        this.indexManager = indexManager;
        this.txnManager = txnManager;
        this.statisticsManager = statisticsManager;
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> executeQuery(
            @RequestBody Map<String, String> req,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String sql = req.get("sql");
        long start = System.currentTimeMillis();
        try {
            List<Token> tokens = new Lexer(sql).tokenize();
            AstNode ast = new Parser(tokens).parse();
            QueryPlanner.PlanNode plan = planner.plan(ast);
            QueryResult result = executor.execute(ast, sessionId);
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("columns", result.columns());
            response.put("rows", result.rows());
            response.put("message", result.message());
            response.put("plan", serializePlan(plan));
            response.put("tokens", tokenSummary(tokens));
            response.put("elapsedMs", elapsed);
            response.put("error", null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("columns", Collections.emptyList());
            response.put("rows", Collections.emptyList());
            response.put("message", null);
            response.put("plan", null);
            response.put("tokens", Collections.emptyList());
            response.put("elapsedMs", elapsed);
            response.put("error", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/schema")
    public ResponseEntity<Map<String, Object>> getSchema() {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Table t : db.getAllTables().values()) {
            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("name", t.name);
            tableMap.put("rowCount", t.getRows().size());

            List<Map<String, Object>> cols = new ArrayList<>();
            for (Table.Column c : t.columns) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.getName());
                col.put("type", c.getType());
                col.put("primaryKey", c.isPrimaryKey());
                col.put("notNull", c.isNotNull());
                cols.add(col);
            }
            tableMap.put("columns", cols);
            tables.add(tableMap);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tables", tables);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/schema/reset")
    public ResponseEntity<Map<String, String>> resetSchema() {
        new ArrayList<>(db.getAllTables().keySet()).forEach(db::dropTable);
        db.seedSampleData();
        wal.clear();
        txnManager.clear();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("message", "Schema reset to sample data");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/wal")
    public ResponseEntity<List<WalEntry>> getWal() {
        return ResponseEntity.ok(wal.getLog());
    }

    @GetMapping("/indexes")
    public ResponseEntity<Map<String, Object>> getIndexes() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("indexes", indexManager.getIndexKeys());
        response.put("namedIndexes", indexManager.getNamedIndexes());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> getTransactions() {
        return ResponseEntity.ok(txnManager.getSummary());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", statisticsManager.getAllStats());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stats/analyze-all")
    public ResponseEntity<Map<String, Object>> analyzeAll() {
        statisticsManager.analyzeAll(db);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "All tables analyzed");
        response.put("stats", statisticsManager.getAllStats());
        return ResponseEntity.ok(response);
    }

    private List<Map<String, String>> tokenSummary(List<Token> tokens) {
        return tokens.stream()
            .filter(t -> !t.type.name().equals("EOF"))
            .map(t -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("type", t.type.name());
                m.put("value", t.value);
                return m;
            })
            .collect(Collectors.toList());
    }

    private Map<String, Object> serializePlan(QueryPlanner.PlanNode node) {
        if (node == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", node.getOperation());
        map.put("description", node.getDescription());
        map.put("stats", node.getStats());
        List<Map<String, Object>> children = new ArrayList<>();
        for (QueryPlanner.PlanNode child : node.getChildren())
            children.add(serializePlan(child));
        map.put("children", children);
        return map;
    }
}

