package com.sqlplayground.api;

import com.sqlplayground.auth.*;
import com.sqlplayground.engine.executor.QueryExecutor;
import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.lexer.Token;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.planner.PlanAnnotator;
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

    private final QueryExecutor executor;
    private final QueryPlanner planner;
    private final WriteAheadLog wal;
    private final IndexManager indexManager;
    private final TransactionManager txnManager;
    private final StatisticsManager statisticsManager;
    private final JwtUtil jwtUtil;
    private final UserDatabaseRegistry userDbRegistry;
    private final QueryHistoryStore historyStore;
    private final UserWalRegistry userWalRegistry;

    public SqlController(QueryExecutor executor, QueryPlanner planner,
                         WriteAheadLog wal, IndexManager indexManager,
                         TransactionManager txnManager,
                         StatisticsManager statisticsManager,
                         JwtUtil jwtUtil,
                         UserDatabaseRegistry userDbRegistry,
                         QueryHistoryStore historyStore,
                         UserWalRegistry userWalRegistry) {
        this.executor          = executor;
        this.planner           = planner;
        this.wal               = wal;
        this.indexManager      = indexManager;
        this.txnManager        = txnManager;
        this.statisticsManager = statisticsManager;
        this.jwtUtil           = jwtUtil;
        this.userDbRegistry    = userDbRegistry;
        this.historyStore      = historyStore;
        this.userWalRegistry   = userWalRegistry;
    }

    private String resolveUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return jwtUtil.validate(auth.substring(7));
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> executeQuery(
            @RequestBody Map<String, String> req,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-Session-Id", defaultValue = "default") String sessionId) {

        String username = resolveUser(auth);
        if (username == null) return unauthorized();

        String sql  = req.get("sql");
        long  start = System.currentTimeMillis();

        InMemoryDatabase userDb = userDbRegistry.getOrCreate(username);
        UserWalRegistry.UserWal userWal = userWalRegistry.getOrCreate(username);

        try {
            List<Token> tokens = new Lexer(sql).tokenize();
            AstNode ast = new Parser(tokens).parse();
            QueryPlanner.PlanNode plan = planner.planWith(ast, userDb);
            QueryResult result = executor.executeFor(ast, sessionId, userDb, userWal);
            PlanAnnotator.annotate(plan, executor.getAndClearLastProfile());
            long elapsed = System.currentTimeMillis() - start;

            historyStore.record(username, sql, true,
                result.message(), result.rows().size(), elapsed);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success",   true);
            response.put("columns",   result.columns());
            response.put("rows",      result.rows());
            response.put("message",   result.message());
            response.put("plan",      serializePlan(plan));
            response.put("tokens",    tokenSummary(tokens));
            response.put("elapsedMs", elapsed);
            response.put("error",     null);
            response.put("sessionId", sessionId);
            response.put("txnActive", txnManager.hasActiveTxn(sessionId));
            response.put("txnId", txnManager.getCurrentTxnId(sessionId));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            historyStore.record(username, sql, false,
                e.getMessage(), 0, elapsed);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success",   false);
            response.put("columns",   Collections.emptyList());
            response.put("rows",      Collections.emptyList());
            response.put("message",   null);
            response.put("plan",      null);
            response.put("tokens",    Collections.emptyList());
            response.put("elapsedMs", elapsed);
            response.put("error",     e.getMessage());
            response.put("sessionId", sessionId);
            response.put("txnActive", txnManager.hasActiveTxn(sessionId));
            response.put("txnId", txnManager.getCurrentTxnId(sessionId));
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/schema")
    public ResponseEntity<Map<String, Object>> getSchema(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String username = resolveUser(auth);
        if (username == null) return unauthorized();

        InMemoryDatabase userDb = userDbRegistry.getOrCreate(username);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Table t : userDb.getAllTables().values()) {
            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("name", t.name);
            tableMap.put("rowCount", t.getRows(0L).size());

            List<Map<String, Object>> cols = new ArrayList<>();
            for (Table.Column c : t.columns) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name",       c.getName());
                col.put("type",       c.getType());
                col.put("primaryKey", c.isPrimaryKey());
                col.put("notNull",    c.isNotNull());
                cols.add(col);
            }
            tableMap.put("columns", cols);
            tables.add(tableMap);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tables", tables);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "50") int limit) {
        String username = resolveUser(auth);
        if (username == null) return unauthorized();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("history", historyStore.getHistory(username, limit));
        response.put("username", username);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/schema/reset")
    public ResponseEntity<Map<String, Object>> resetSchema(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String username = resolveUser(auth);
        if (username == null) return unauthorized();

        InMemoryDatabase userDb = userDbRegistry.getOrCreate(username);
        new ArrayList<>(userDb.getAllTables().keySet()).forEach(userDb::dropTable);
        userDb.seedSampleData();

        // Clear the user's WAL and start fresh
        UserWalRegistry.UserWal userWal = userWalRegistry.getOrCreate(username);
        userWal.clear();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Schema reset for user: " + username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/wal")
    public ResponseEntity<List<WalEntry>> getWal(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String username = resolveUser(auth);
        if (username == null) return ResponseEntity.status(401).build();
        UserWalRegistry.UserWal userWal = userWalRegistry.getOrCreate(username);
        return ResponseEntity.ok(userWal.getLog());
    }

    @GetMapping("/indexes")
    public ResponseEntity<Map<String, Object>> getIndexes(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolveUser(auth) == null) return unauthorized();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("indexes",      indexManager.getIndexKeys());
        response.put("namedIndexes", indexManager.getNamedIndexes());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> getTransactions(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolveUser(auth) == null) return unauthorized();
        return ResponseEntity.ok(txnManager.getSummary());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolveUser(auth) == null) return unauthorized();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", statisticsManager.getAllStats());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stats/analyze-all")
    public ResponseEntity<Map<String, Object>> analyzeAll(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String username = resolveUser(auth);
        if (username == null) return unauthorized();
        InMemoryDatabase userDb = userDbRegistry.getOrCreate(username);
        statisticsManager.analyzeAll(userDb);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "All tables analyzed");
        response.put("stats",   statisticsManager.getAllStats());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", false);
        resp.put("error",   "Unauthorized - please log in");
        return ResponseEntity.status(401).body(resp);
    }

    private List<Map<String, String>> tokenSummary(List<Token> tokens) {
        return tokens.stream()
            .filter(t -> !t.type.name().equals("EOF"))
            .map(t -> { Map<String, String> m = new LinkedHashMap<>();
                        m.put("type", t.type.name()); m.put("value", t.value);
                        return m; })
            .collect(Collectors.toList());
    }

    private Map<String, Object> serializePlan(QueryPlanner.PlanNode node) {
        if (node == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation",   node.getOperation());
        map.put("description", node.getDescription());
        map.put("stats",       node.getStats());
        map.put("actualRows",  node.getActualRows());
        List<Map<String, Object>> children = new ArrayList<>();
        for (QueryPlanner.PlanNode child : node.getChildren())
            children.add(serializePlan(child));
        map.put("children", children);
        return map;
    }
}
