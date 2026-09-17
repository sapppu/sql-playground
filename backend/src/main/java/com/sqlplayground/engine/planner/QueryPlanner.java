package com.sqlplayground.engine.planner;

import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.storage.InMemoryDatabase;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class QueryPlanner {

    /** Right/left row-count estimate at or above this uses a hash join. */
    public static final int HASH_JOIN_THRESHOLD = 200;

    private InMemoryDatabase db;
    private final IndexManager indexManager;
    private final StatisticsManager statisticsManager;

    public QueryPlanner(InMemoryDatabase db, IndexManager indexManager, StatisticsManager statisticsManager) {
        this.db = db;
        this.indexManager = indexManager;
        this.statisticsManager = statisticsManager;
    }

    /**
     * Strategy selection shared by planner and executor: the build side is
     * the smaller input (per cost-based convention) and its row-count
     * estimate decides. Estimates come from stats with actual-size fallback
     * at the call sites.
     */
    public static String chooseStrategy(long leftEst, long rightEst) {
        long buildRows = Math.min(leftEst, rightEst);
        return buildRows >= HASH_JOIN_THRESHOLD ? "hash_join" : "nested_loop";
    }

    /** Row-count estimate for a table: stats cache first, actual size fallback. */
    public long estimateRows(String tableName) {
        var stats = statisticsManager.getStats(tableName);
        if (stats.isPresent()) return stats.get().rowCount;
        if (db.tableExists(tableName)) return db.getTable(tableName).getRows().size();
        return 0;
    }

    public PlanNode planWith(AstNode ast, InMemoryDatabase userDb) {
        InMemoryDatabase original = this.db;
        this.db = userDb;
        try {
            return plan(ast);
        } finally {
            this.db = original;
        }
    }

    public PlanNode plan(AstNode ast) {
        if (ast.type == AstNode.NodeType.SELECT_STMT)
            return planSelect((AstNode.SelectStatement) ast);

        if (ast.type == AstNode.NodeType.INSERT_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.InsertStatement) ast).tableName);
            return new PlanNode("INSERT", "Insert rows into table", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.CREATE_TABLE_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.CreateTableStatement) ast).tableName);
            return new PlanNode("CREATE", "Create new table", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.DROP_TABLE_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.DropTableStatement) ast).tableName);
            return new PlanNode("DROP", "Drop table", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.DELETE_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.DeleteStatement) ast).tableName);
            return new PlanNode("DELETE", "Delete matching rows", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.UPDATE_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.UpdateStatement) ast).tableName);
            return new PlanNode("UPDATE", "Update matching rows", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.CREATE_INDEX_STMT) {
            AstNode.CreateIndexStatement idx = (AstNode.CreateIndexStatement) ast;
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("index", idx.indexName);
            stats.put("table", idx.tableName);
            stats.put("column", idx.columnName);
            return new PlanNode("CREATE", "Create B-Tree index", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.DROP_INDEX_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("index", ((AstNode.DropIndexStatement) ast).indexName);
            return new PlanNode("DROP", "Drop index", Collections.emptyList(), stats);
        }
        if (ast.type == AstNode.NodeType.ANALYZE_STMT) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("table", ((AstNode.AnalyzeStatement) ast).tableName);
            return new PlanNode("ANALYZE", "Collect column statistics", Collections.emptyList(), stats);
        }

        return new PlanNode("UNKNOWN", "", Collections.emptyList(), Collections.emptyMap());
    }

    private PlanNode planSelect(AstNode.SelectStatement stmt) {
        String tableName = resolveTableName(stmt.from);
        int tableSize = db.tableExists(tableName) ? db.getTable(tableName).getRows().size() : 0;

        // Check if we can use an INDEX_SCAN instead of SEQ_SCAN
        IndexScanInfo indexScan = checkIndexScan(tableName, stmt.where);

        PlanNode node;
        if (indexScan != null) {
            // INDEX_SCAN
            int estRows = Math.max(1, tableSize / 8);
            Map<String, Object> scanStats = new LinkedHashMap<>();
            scanStats.put("table", tableName);
            scanStats.put("index", tableName + "." + indexScan.columnName);
            scanStats.put("lookup_key", indexScan.lookupValue);
            scanStats.put("est_rows", estRows);
            scanStats.put("estimatedRows", estRows);
            scanStats.put("selectivity", format4((double) estRows / Math.max(tableSize, 1)));
            scanStats.put("cost", (int) Math.ceil(Math.log(tableSize + 1) / Math.log(2)));
            node = new PlanNode("INDEX_SCAN", "B-Tree index lookup on " + indexScan.columnName,
                Collections.emptyList(), scanStats);
        } else {
            // SEQ_SCAN
            Map<String, Object> scanStats = new LinkedHashMap<>();
            scanStats.put("table", tableName);
            scanStats.put("rows", tableSize);
            scanStats.put("estimatedRows", tableSize);
            scanStats.put("cost", tableSize);
            node = new PlanNode("SEQ_SCAN", "Full table scan", Collections.emptyList(), scanStats);
        }

        // ---- JOIN plan nodes ----
        // Node operation carries the STRATEGY (HASH_JOIN / NESTED_LOOP_JOIN);
        // the join TYPE (INNER / LEFT) rides along in stats as join_type.
        long leftEst = tableSize;
        String leftName = tableName;
        for (AstNode.JoinClause join : stmt.joins) {
            long rightEst = estimateRows(join.rightTable);

            String strategy = chooseStrategy(leftEst, rightEst);
            String operation = "hash_join".equals(strategy) ? "HASH_JOIN" : "NESTED_LOOP_JOIN";
            long buildRows = Math.min(leftEst, rightEst);
            // Build side is the smaller input; probe side is the larger one.
            boolean rightIsBuild = rightEst <= leftEst;
            String buildName = rightIsBuild ? join.rightTable : leftName;
            String probeName = rightIsBuild ? leftName : join.rightTable;
            long probeRows = Math.max(leftEst, rightEst);
            long outputEst = estimateJoinOutput(leftEst, rightEst, leftName, join);

            Map<String, Object> joinStats = new LinkedHashMap<>();
            joinStats.put("join_type", join.joinType);
            joinStats.put("left_table", leftName);
            joinStats.put("right_table", join.rightTable);
            joinStats.put("strategy", strategy);
            joinStats.put("estimated_rows", buildRows);
            joinStats.put("estimatedRows", outputEst);
            joinStats.put("selectivity", format4((double) outputEst / Math.max(leftEst, 1)));
            joinStats.put("build_table", buildName);
            joinStats.put("probe_table", probeName);
            joinStats.put("on", describeExpr(join.onCondition));
            joinStats.put("summary", "Chose " + operation + ": build side (" + buildName
                + ", ~" + buildRows + " rows) "
                + ("hash_join".equals(strategy)
                    ? "fits " + HASH_JOIN_THRESHOLD + "-row threshold"
                    : "below " + HASH_JOIN_THRESHOLD + "-row threshold")
                + "; probe side (" + probeName + ", ~" + probeRows + " rows).");

            node = new PlanNode(
                operation,
                join.joinType + " JOIN " + join.rightTable
                    + " ON " + describeExpr(join.onCondition)
                    + " [" + strategy + "]",
                Collections.singletonList(node),
                joinStats
            );
            leftEst = Math.max(leftEst, rightEst);
            leftName = "intermediate";
        }

        // FILTER
        if (stmt.where != null) {
            int inputRows = indexScan != null ? Math.max(1, tableSize / 8) : tableSize;
            double selectivity = estimateWhereSelectivity(tableName, stmt.where);
            int outRows = indexScan != null ? inputRows : (int)(tableSize * selectivity);
            Map<String, Object> filterStats = new LinkedHashMap<>();
            filterStats.put("condition", describeExpr(stmt.where));
            filterStats.put("selectivity", String.format("%.4f", selectivity));
            filterStats.put("est_rows", outRows);
            filterStats.put("estimatedRows", outRows);
            filterStats.put("cost", inputRows);
            node = new PlanNode("FILTER", "WHERE " + describeExpr(stmt.where),
                Collections.singletonList(node), filterStats);
        }

        // HASH_AGG
        if (!stmt.groupBy.isEmpty()) {
            List<String> groupCols = stmt.groupBy.stream()
                .map(this::describeExpr).collect(Collectors.toList());
            Map<String, Object> aggStats = new LinkedHashMap<>();
            aggStats.put("group_by", groupCols);
            aggStats.put("cost", tableSize / 2);
            node = new PlanNode("HASH_AGG", "Group + aggregate",
                Collections.singletonList(node), aggStats);
        }

        // SORT
        if (!stmt.orderBy.isEmpty()) {
            List<String> sortKeys = new ArrayList<>();
            for (AstNode o : stmt.orderBy) {
                AstNode.OrderItem oi = (AstNode.OrderItem) o;
                sortKeys.add(describeExpr(oi.expr) + (oi.ascending ? " ASC" : " DESC"));
            }
            Map<String, Object> sortStats = new LinkedHashMap<>();
            sortStats.put("keys", sortKeys);
            sortStats.put("cost", (int)(tableSize * Math.log(tableSize + 1)));
            node = new PlanNode("SORT", "Order results",
                Collections.singletonList(node), sortStats);
        }

        // LIMIT
        if (stmt.limit != null || stmt.offset != null) {
            Map<String, Object> limitStats = new LinkedHashMap<>();
            limitStats.put("limit", stmt.limit);
            limitStats.put("offset", stmt.offset != null ? stmt.offset : 0);
            if (stmt.limit != null) limitStats.put("estimatedRows", stmt.limit);
            limitStats.put("cost", 1);
            node = new PlanNode("LIMIT", "Restrict output rows",
                Collections.singletonList(node), limitStats);
        }

        // PROJECT
        List<String> projCols = stmt.columns.stream()
            .map(this::describeExpr).collect(Collectors.toList());
        Map<String, Object> projStats = new LinkedHashMap<>();
        projStats.put("columns", projCols);
        projStats.put("cost", 1);
        node = new PlanNode("PROJECT", "Select columns",
            Collections.singletonList(node), projStats);

        return node;
    }

    /**
     * Estimated join OUTPUT rows (as opposed to {@code estimated_rows},
     * which sizes the build input). For equi-joins of two column refs we
     * use the classic 1/max(ndv) selectivity, so the number moves with
     * ANALYZE freshness; otherwise we fall back to 0.3 of the cross
     * product, matching the codebase convention for unknown selectivity.
     */
    private long estimateJoinOutput(long leftEst, long rightEst, String leftName, AstNode.JoinClause join) {
        double sel = 0.3;
        if (join.onCondition instanceof AstNode.BinaryExpr) {
            AstNode.BinaryExpr be = (AstNode.BinaryExpr) join.onCondition;
            if ("=".equals(be.operator)
                    && be.left instanceof AstNode.ColumnRef
                    && be.right instanceof AstNode.ColumnRef) {
                long ndv = Math.max(
                    columnNdv(resolveJoinSideTable(((AstNode.ColumnRef) be.left).table, leftName, join.rightTable),
                        ((AstNode.ColumnRef) be.left).column),
                    columnNdv(resolveJoinSideTable(((AstNode.ColumnRef) be.right).table, leftName, join.rightTable),
                        ((AstNode.ColumnRef) be.right).column));
                if (ndv > 0) sel = 1.0 / ndv;
            }
        }
        return Math.max(1, Math.round(leftEst * (double) rightEst * sel));
    }

    private String resolveJoinSideTable(String qualifier, String leftName, String rightName) {
        if (qualifier == null) return leftName;
        if (qualifier.equalsIgnoreCase(rightName)) return rightName;
        return leftName;
    }

    private long columnNdv(String tableName, String columnName) {
        return statisticsManager.getStats(tableName)
            .map(ts -> ts.getColumn(columnName))
            .map(cs -> cs.ndv)
            .orElse(0L);
    }

    private static String format4(double value) {
        return String.format("%.4f", value);
    }
    private IndexScanInfo checkIndexScan(String tableName, AstNode where) {
        if (where == null) return null;
        if (!(where instanceof AstNode.BinaryExpr)) return null;

        AstNode.BinaryExpr be = (AstNode.BinaryExpr) where;
        if (!"=".equals(be.operator)) return null;

        String columnName = null;
        String lookupValue = null;

        if (be.left instanceof AstNode.ColumnRef && be.right instanceof AstNode.Literal) {
            columnName = ((AstNode.ColumnRef) be.left).column;
            lookupValue = String.valueOf(((AstNode.Literal) be.right).literalValue);
        } else if (be.right instanceof AstNode.ColumnRef && be.left instanceof AstNode.Literal) {
            columnName = ((AstNode.ColumnRef) be.right).column;
            lookupValue = String.valueOf(((AstNode.Literal) be.left).literalValue);
        }

        if (columnName == null) return null;
        if (!indexManager.indexExists(tableName, columnName)) return null;

        return new IndexScanInfo(columnName, lookupValue);
    }

    private static class IndexScanInfo {
        final String columnName;
        final String lookupValue;
        IndexScanInfo(String columnName, String lookupValue) {
            this.columnName = columnName;
            this.lookupValue = lookupValue;
        }
    }

    private double estimateWhereSelectivity(String tableName, AstNode where) {
        if (where == null) return 1.0;
        if (where instanceof AstNode.BinaryExpr) {
            AstNode.BinaryExpr be = (AstNode.BinaryExpr) where;
            String op = be.operator;
            if ("AND".equals(op)) {
                return estimateWhereSelectivity(tableName, be.left)
                     * estimateWhereSelectivity(tableName, be.right);
            }
            if ("OR".equals(op)) {
                double leftSel = estimateWhereSelectivity(tableName, be.left);
                double rightSel = estimateWhereSelectivity(tableName, be.right);
                return Math.min(1.0, leftSel + rightSel);
            }
            String columnName = null;
            Object literal = null;
            if (be.left instanceof AstNode.ColumnRef && be.right instanceof AstNode.Literal) {
                columnName = ((AstNode.ColumnRef) be.left).column;
                literal = ((AstNode.Literal) be.right).literalValue;
            } else if (be.right instanceof AstNode.ColumnRef && be.left instanceof AstNode.Literal) {
                columnName = ((AstNode.ColumnRef) be.right).column;
                literal = ((AstNode.Literal) be.left).literalValue;
            }
            if (columnName != null && literal != null) {
                return statisticsManager.estimateSelectivity(tableName, columnName, op, literal);
            }
            return 0.3;
        }
        return 0.3;
    }

    private String describeExpr(AstNode node) {
        if (node instanceof AstNode.ColumnRef) {
            AstNode.ColumnRef cr = (AstNode.ColumnRef) node;
            return cr.table != null ? cr.table + "." + cr.column : cr.column;
        }
        if (node instanceof AstNode.Literal) {
            AstNode.Literal lit = (AstNode.Literal) node;
            return lit.literalValue == null ? "NULL" : lit.literalValue.toString();
        }
        if (node instanceof AstNode.BinaryExpr) {
            AstNode.BinaryExpr be = (AstNode.BinaryExpr) node;
            return describeExpr(be.left) + " " + be.operator + " " + describeExpr(be.right);
        }
        if (node instanceof AstNode.FunctionCall) {
            AstNode.FunctionCall fn = (AstNode.FunctionCall) node;
            String args = fn.args.stream().map(this::describeExpr)
                .collect(Collectors.joining(", "));
            return fn.funcName + "(" + args + ")";
        }
        if (node instanceof AstNode.Alias) {
            AstNode.Alias a = (AstNode.Alias) node;
            return describeExpr(a.inner) + " AS " + a.alias;
        }
        if (node instanceof AstNode.OrderItem) {
            AstNode.OrderItem oi = (AstNode.OrderItem) node;
            return describeExpr(oi.expr) + (oi.ascending ? " ASC" : " DESC");
        }
        if (node.type == AstNode.NodeType.WILDCARD) return "*";
        return node.value;
    }

    private String resolveTableName(AstNode from) {
        if (from == null) return "?";
        if (from.type == AstNode.NodeType.ALIAS) return resolveTableName(from.children.get(0));
        return from.value;
    }

    // ---- Plain class instead of record for Java 17 compatibility ----
    public static class PlanNode {
        private final String operation;
        private final String description;
        private final List<PlanNode> children;
        private final Map<String, Object> stats;
        // Rows actually produced by this stage at execution time.
        // Null until the controller annotates the plan post-execution.
        private Integer actualRows;

        public PlanNode(String operation, String description,
                        List<PlanNode> children, Map<String, Object> stats) {
            this.operation = operation;
            this.description = description;
            this.children = children;
            this.stats = stats;
        }

        public String getOperation()      { return operation; }
        public String getDescription()    { return description; }
        public List<PlanNode> getChildren(){ return children; }
        public Map<String, Object> getStats(){ return stats; }
        public Integer getActualRows()    { return actualRows; }
        public void setActualRows(Integer actualRows) { this.actualRows = actualRows; }
    }
}
