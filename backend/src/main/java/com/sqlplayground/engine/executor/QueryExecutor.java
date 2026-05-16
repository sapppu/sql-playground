package com.sqlplayground.engine.executor;

import com.sqlplayground.engine.index.BTreeIndex;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.stats.ColumnStats;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final InMemoryDatabase db;
    private final WriteAheadLog wal;
    private final IndexManager indexManager;
    private final TransactionManager txnManager;
    private final StatisticsManager statisticsManager;

    public QueryExecutor(InMemoryDatabase db, WriteAheadLog wal, IndexManager indexManager, TransactionManager txnManager, StatisticsManager statisticsManager) {
        this.db = db;
        this.wal = wal;
        this.indexManager = indexManager;
        this.txnManager = txnManager;
        this.statisticsManager = statisticsManager;
    }

    public QueryResult execute(AstNode ast) {
        return execute(ast, null);
    }
    public QueryResult execute(AstNode ast, String sessionId) {
        // Ensure tables have TransactionManager set for MVCC
        for (Table t : db.getAllTables().values()) {
            if (t.getTransactionManager() == null) {
                t.setTransactionManager(txnManager);
            }
        }
        if (ast.type == AstNode.NodeType.SELECT_STMT)
            return executeSelect((AstNode.SelectStatement) ast, sessionId);
        if (ast.type == AstNode.NodeType.INSERT_STMT)
            return executeInsert((AstNode.InsertStatement) ast, sessionId);
        if (ast.type == AstNode.NodeType.CREATE_TABLE_STMT)
            return executeCreate((AstNode.CreateTableStatement) ast);
        if (ast.type == AstNode.NodeType.DROP_TABLE_STMT)
            return executeDrop((AstNode.DropTableStatement) ast);
        if (ast.type == AstNode.NodeType.DELETE_STMT)
            return executeDelete((AstNode.DeleteStatement) ast, sessionId);
        if (ast.type == AstNode.NodeType.UPDATE_STMT)
            return executeUpdate((AstNode.UpdateStatement) ast, sessionId);
        if (ast.type == AstNode.NodeType.CREATE_INDEX_STMT)
            return executeCreateIndex((AstNode.CreateIndexStatement) ast);
        if (ast.type == AstNode.NodeType.DROP_INDEX_STMT)
            return executeDropIndex((AstNode.DropIndexStatement) ast);
        if (ast.type == AstNode.NodeType.BEGIN_STMT) {
            long txnId = txnManager.begin(sessionId != null ? sessionId : "default");
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), "Transaction #" + txnId + " started");
        }
        if (ast.type == AstNode.NodeType.COMMIT_STMT) {
            long txnId = txnManager.commit(sessionId != null ? sessionId : "default");
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), "Transaction #" + txnId + " committed");
        }
        if (ast.type == AstNode.NodeType.ROLLBACK_STMT) {
            long txnId = txnManager.rollback(sessionId != null ? sessionId : "default");
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), "Transaction #" + txnId + " rolled back");
        }
        if (ast.type == AstNode.NodeType.ANALYZE_STMT) {
            AstNode.AnalyzeStatement stmt = (AstNode.AnalyzeStatement) ast;
            Table table = db.getTable(stmt.tableName);
            statisticsManager.analyze(stmt.tableName, table);
            TableStatsSummary summary = summarizeStats(stmt.tableName);
            return new QueryResult(summary.columns, summary.rows,
                "Analyzed '" + stmt.tableName + "' — " + summary.columns.size() + " columns");
        }
        throw new ExecutionException("Unknown statement type: " + ast.type);
    }

    // ---- SELECT ----
    private QueryResult executeSelect(AstNode.SelectStatement stmt, String sessionId) {
        long txnId = txnManager.getCurrentTxn(sessionId);
        String tableName = resolveTableName(stmt.from);
        Table table = db.getTable(tableName);

        List<Map<String, Object>> rows;

        // Try to use an index for simple equality WHERE clauses (only when no JOINs)
        if (stmt.joins.isEmpty()) {
            IndexLookupResult indexResult = tryIndexLookup(tableName, stmt.where);
            if (indexResult != null) {
                rows = new ArrayList<>(indexResult.rows);
                if (stmt.where != null) {
                    rows = rows.stream()
                        .filter(r -> isTruthy(evalExpr(stmt.where, r)))
                        .collect(Collectors.toList());
                }
            } else {
                rows = new ArrayList<>(table.getRows(txnId));
                if (stmt.where != null)
                    rows = rows.stream()
                        .filter(r -> isTruthy(evalExpr(stmt.where, r)))
                        .collect(Collectors.toList());
            }
        } else {
            // JOINs: start with base table rows (qualified column names)
            rows = new ArrayList<>();
            for (Map<String, Object> row : table.getRows(txnId)) {
                Map<String, Object> qualified = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    qualified.put(tableName + "." + e.getKey(), e.getValue());
                    qualified.put(e.getKey(), e.getValue()); // unqualified fallback
                }
                rows.add(qualified);
            }

            // Execute each JOIN
            for (AstNode.JoinClause join : stmt.joins) {
                Table rightTable = db.getTable(join.rightTable);
                int rightSize = rightTable.getRows(txnId).size();

                if (rightSize <= 200) {
                    rows = nestedLoopJoin(rows, join.rightTable, rightTable, join.onCondition, join.joinType, txnId);
                } else {
                    rows = hashJoin(rows, join.rightTable, rightTable, join.onCondition, join.joinType, txnId);
                }
            }

            // Apply WHERE after JOINs
            if (stmt.where != null)
                rows = rows.stream()
                    .filter(r -> isTruthy(evalExpr(stmt.where, r)))
                    .collect(Collectors.toList());
        }

        if (!stmt.groupBy.isEmpty())
            rows = groupBy(rows, stmt.groupBy, stmt.columns);

        if (!stmt.orderBy.isEmpty())
            rows = sortRows(rows, stmt.orderBy);

        if (stmt.offset != null)
            rows = rows.stream().skip(stmt.offset).collect(Collectors.toList());

        if (stmt.limit != null)
            rows = rows.stream().limit(stmt.limit).collect(Collectors.toList());

        // For JOINs, resolve columns differently
        List<String> colNames;
        List<Map<String, Object>> projected;
        if (!stmt.joins.isEmpty()) {
            colNames = resolveJoinColumnNames(stmt.columns, rows);
            projected = projectJoinRows(rows, stmt.columns, colNames);
        } else {
            colNames = resolveColumnNames(stmt.columns, table);
            projected = projectRows(rows, stmt.columns, table, colNames);
        }

        if (stmt.distinct)
            projected = distinct(projected);

        return new QueryResult(colNames, projected, projected.size() + " row(s) returned");
    }

    // ---- JOIN methods ----

    private List<Map<String, Object>> nestedLoopJoin(List<Map<String, Object>> left,
                                                       String rightTableName, Table rightTable,
                                                       AstNode onCondition, String joinType,
                                                       long txnId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> lRow : left) {
            boolean matched = false;
            for (Map<String, Object> rRow : rightTable.getRows(txnId)) {
                Map<String, Object> combined = new LinkedHashMap<>(lRow);
                for (Map.Entry<String, Object> e : rRow.entrySet()) {
                    combined.put(rightTableName + "." + e.getKey(), e.getValue());
                    if (!combined.containsKey(e.getKey())) {
                        combined.put(e.getKey(), e.getValue());
                    }
                }
                if (isTruthy(evalExpr(onCondition, combined))) {
                    result.add(combined);
                    matched = true;
                }
            }
            if (!matched && "LEFT".equals(joinType)) {
                Map<String, Object> padded = new LinkedHashMap<>(lRow);
                for (Table.Column col : rightTable.columns) {
                    padded.put(rightTableName + "." + col.getName(), null);
                }
                result.add(padded);
            }
        }
        return result;
    }

    private List<Map<String, Object>> hashJoin(List<Map<String, Object>> left,
                                                 String rightTableName, Table rightTable,
                                                 AstNode onCondition, String joinType,
                                                 long txnId) {
        // Extract join key columns from ON condition (expects table.col = table.col)
        String rightKeyCol = null;
        String leftKeyCol = null;
        if (onCondition instanceof AstNode.BinaryExpr) {
            AstNode.BinaryExpr be = (AstNode.BinaryExpr) onCondition;
            if ("=".equals(be.operator)) {
                if (be.left instanceof AstNode.ColumnRef && be.right instanceof AstNode.ColumnRef) {
                    AstNode.ColumnRef leftRef = (AstNode.ColumnRef) be.left;
                    AstNode.ColumnRef rightRef = (AstNode.ColumnRef) be.right;
                    // Determine which is left table and which is right
                    if (rightTableName.equalsIgnoreCase(rightRef.table)) {
                        rightKeyCol = rightRef.column;
                        leftKeyCol = leftRef.table != null ? leftRef.table + "." + leftRef.column : leftRef.column;
                    } else if (rightTableName.equalsIgnoreCase(leftRef.table)) {
                        rightKeyCol = leftRef.column;
                        leftKeyCol = rightRef.table != null ? rightRef.table + "." + rightRef.column : rightRef.column;
                    }
                }
            }
        }

        // Fallback to nested loop if we can't extract hash key
        if (rightKeyCol == null) {
            return nestedLoopJoin(left, rightTableName, rightTable, onCondition, joinType, txnId);
        }

        // Build hash map on right table
        Map<String, List<Map<String, Object>>> hashMap = new LinkedHashMap<>();
        for (Map<String, Object> rRow : rightTable.getRows(txnId)) {
            Object keyVal = rRow.get(rightKeyCol);
            String key = keyVal == null ? "__null__" : keyVal.toString().toLowerCase();
            hashMap.computeIfAbsent(key, k -> new ArrayList<>()).add(rRow);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> lRow : left) {
            Object lKeyVal = lRow.get(leftKeyCol);
            String lKey = lKeyVal == null ? "__null__" : lKeyVal.toString().toLowerCase();
            List<Map<String, Object>> matches = hashMap.getOrDefault(lKey, Collections.emptyList());

            if (!matches.isEmpty()) {
                for (Map<String, Object> rRow : matches) {
                    Map<String, Object> combined = new LinkedHashMap<>(lRow);
                    for (Map.Entry<String, Object> e : rRow.entrySet()) {
                        combined.put(rightTableName + "." + e.getKey(), e.getValue());
                        if (!combined.containsKey(e.getKey())) {
                            combined.put(e.getKey(), e.getValue());
                        }
                    }
                    result.add(combined);
                }
            } else if ("LEFT".equals(joinType)) {
                Map<String, Object> padded = new LinkedHashMap<>(lRow);
                for (Table.Column col : rightTable.columns) {
                    padded.put(rightTableName + "." + col.getName(), null);
                }
                result.add(padded);
            }
        }
        return result;
    }

    private List<String> resolveJoinColumnNames(List<AstNode> cols, List<Map<String, Object>> rows) {
        if (cols.size() == 1 && cols.get(0).type == AstNode.NodeType.WILDCARD) {
            if (rows.isEmpty()) return Collections.emptyList();
            // Return all unique keys preserving order, but skip unqualified duplicates
            List<String> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String key : rows.get(0).keySet()) {
                if (key.contains(".")) {
                    result.add(key);
                    seen.add(key);
                }
            }
            // Also add unqualified keys not already present as qualified
            for (String key : rows.get(0).keySet()) {
                if (!key.contains(".") && !seen.contains(key)) {
                    result.add(key);
                }
            }
            return result;
        }
        return cols.stream().map(this::colLabel).collect(Collectors.toList());
    }

    private List<Map<String, Object>> projectJoinRows(List<Map<String, Object>> rows,
                                                       List<AstNode> cols,
                                                       List<String> labels) {
        if (cols.size() == 1 && cols.get(0).type == AstNode.NodeType.WILDCARD) {
            // Filter to only include the resolved column names
            Set<String> labelSet = new LinkedHashSet<>(labels);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> filtered = new LinkedHashMap<>();
                for (String label : labelSet) {
                    filtered.put(label, row.get(label));
                }
                result.add(filtered);
            }
            return result;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                AstNode col = cols.get(i);
                AstNode expr = (col instanceof AstNode.Alias)
                    ? ((AstNode.Alias) col).inner
                    : col;
                projected.put(labels.get(i), evalExpr(expr, row));
            }
            result.add(projected);
        }
        return result;
    }

    /**
     * Attempts to use an index for a simple equality WHERE clause.
     * Returns the index-scanned rows if applicable, null otherwise.
     */
    @SuppressWarnings("unchecked")
    private IndexLookupResult tryIndexLookup(String tableName, AstNode where) {
        if (where == null) return null;
        if (!(where instanceof AstNode.BinaryExpr)) return null;

        AstNode.BinaryExpr be = (AstNode.BinaryExpr) where;

        // Only handle simple equality: column = literal
        if (!"=".equals(be.operator)) return null;

        String columnName = null;
        Object literalValue = null;

        if (be.left instanceof AstNode.ColumnRef && be.right instanceof AstNode.Literal) {
            columnName = ((AstNode.ColumnRef) be.left).column;
            literalValue = ((AstNode.Literal) be.right).literalValue;
        } else if (be.right instanceof AstNode.ColumnRef && be.left instanceof AstNode.Literal) {
            columnName = ((AstNode.ColumnRef) be.right).column;
            literalValue = ((AstNode.Literal) be.left).literalValue;
        }

        if (columnName == null || literalValue == null) return null;

        Optional<BTreeIndex> idx = indexManager.getIndex(tableName, columnName);
        if (idx.isEmpty()) return null;

        if (literalValue instanceof Comparable) {
            List<Map<String, Object>> results = idx.get().search((Comparable) literalValue);
            return new IndexLookupResult(columnName, results);
        }
        return null;
    }

    private static class IndexLookupResult {
        final String column;
        final List<Map<String, Object>> rows;
        IndexLookupResult(String column, List<Map<String, Object>> rows) {
            this.column = column;
            this.rows = rows;
        }
    }

    private List<Map<String, Object>> groupBy(List<Map<String, Object>> rows,
                                               List<AstNode> groupByCols,
                                               List<AstNode> selectCols) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            StringBuilder keyBuilder = new StringBuilder();
            for (AstNode g : groupByCols) {
                keyBuilder.append(evalExpr(g, row)).append("|");
            }
            String key = keyBuilder.toString();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> aggRow = new LinkedHashMap<>(group.get(0));
            for (AstNode col : selectCols) {
                if (col instanceof AstNode.FunctionCall) {
                    AstNode.FunctionCall fn = (AstNode.FunctionCall) col;
                    aggRow.put(fn.funcName + "(*)", evalAggregate(fn, group));
                }
            }
            result.add(aggRow);
        }
        return result;
    }

    private Object evalAggregate(AstNode.FunctionCall fn, List<Map<String, Object>> group) {
        if ("COUNT".equals(fn.funcName)) return (long) group.size();
        if ("SUM".equals(fn.funcName))
            return group.stream().mapToDouble(r -> toDouble(evalExpr(fn.args.get(0), r))).sum();
        if ("AVG".equals(fn.funcName))
            return group.stream().mapToDouble(r -> toDouble(evalExpr(fn.args.get(0), r))).average().orElse(0);
        if ("MAX".equals(fn.funcName))
            return group.stream().mapToDouble(r -> toDouble(evalExpr(fn.args.get(0), r))).max().orElse(0);
        if ("MIN".equals(fn.funcName))
            return group.stream().mapToDouble(r -> toDouble(evalExpr(fn.args.get(0), r))).min().orElse(0);
        return null;
    }

    private List<Map<String, Object>> sortRows(List<Map<String, Object>> rows, List<AstNode> orderItems) {
        Comparator<Map<String, Object>> comp = null;
        for (AstNode item : orderItems) {
            AstNode.OrderItem oi = (AstNode.OrderItem) item;
            Comparator<Map<String, Object>> c = Comparator.comparing(
                r -> toComparable(evalExpr(oi.expr, r)),
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            if (!oi.ascending) c = c.reversed();
            comp = (comp == null) ? c : comp.thenComparing(c);
        }
        if (comp != null) rows.sort(comp);
        return rows;
    }

    private List<String> resolveColumnNames(List<AstNode> cols, Table table) {
        if (cols.size() == 1 && cols.get(0).type == AstNode.NodeType.WILDCARD) {
            return table.columns.stream().map(Table.Column::getName).collect(Collectors.toList());
        }
        return cols.stream().map(this::colLabel).collect(Collectors.toList());
    }

    private String colLabel(AstNode node) {
        if (node instanceof AstNode.ColumnRef)   return ((AstNode.ColumnRef) node).column;
        if (node instanceof AstNode.Alias)        return ((AstNode.Alias) node).alias;
        if (node instanceof AstNode.FunctionCall) {
            AstNode.FunctionCall fn = (AstNode.FunctionCall) node;
            String arg = fn.args.isEmpty() ? "*" : colLabel(fn.args.get(0));
            return fn.funcName + "(" + arg + ")";
        }
        return node.value;
    }

    private List<Map<String, Object>> projectRows(List<Map<String, Object>> rows,
                                                   List<AstNode> cols,
                                                   Table table,
                                                   List<String> labels) {
        if (cols.size() == 1 && cols.get(0).type == AstNode.NodeType.WILDCARD) return rows;

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                AstNode col = cols.get(i);
                AstNode expr = (col instanceof AstNode.Alias)
                    ? ((AstNode.Alias) col).inner
                    : col;
                projected.put(labels.get(i), evalExpr(expr, row));
            }
            result.add(projected);
        }
        return result;
    }

    private List<Map<String, Object>> distinct(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (seen.add(row.toString())) result.add(row);
        }
        return result;
    }

    // ---- INSERT ----
    private QueryResult executeInsert(AstNode.InsertStatement stmt, String sessionId) {
        long txnId = txnManager.getCurrentTxn(sessionId);
        Table table = db.getTable(stmt.tableName);
        int count = 0;
        for (List<AstNode> vals : stmt.valueSets) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < stmt.columns.size(); i++)
                row.put(stmt.columns.get(i), evalExpr(vals.get(i), Collections.emptyMap()));
            if (stmt.columns.isEmpty()) {
                for (int i = 0; i < table.columns.size() && i < vals.size(); i++)
                    row.put(table.columns.get(i).getName(), evalExpr(vals.get(i), Collections.emptyMap()));
            }
            wal.append("INSERT", stmt.tableName, row);
            table.insertRow(row, txnId);
            count++;
        }
        indexManager.invalidate(stmt.tableName);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(), count + " row(s) inserted");
    }

    // ---- CREATE TABLE ----
    private QueryResult executeCreate(AstNode.CreateTableStatement stmt) {
        List<Table.Column> cols = new ArrayList<>();
        for (AstNode.ColumnDef d : stmt.columnDefs)
            cols.add(new Table.Column(d.columnName, d.dataType, d.primaryKey, d.notNull));
        db.createTable(new Table(stmt.tableName, cols));
        return new QueryResult(Collections.emptyList(), Collections.emptyList(),
            "Table '" + stmt.tableName + "' created");
    }

    // ---- DROP TABLE ----
    private QueryResult executeDrop(AstNode.DropTableStatement stmt) {
        db.dropTable(stmt.tableName);
        indexManager.invalidate(stmt.tableName);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(),
            "Table '" + stmt.tableName + "' dropped");
    }

    // ---- CREATE INDEX ----
    private QueryResult executeCreateIndex(AstNode.CreateIndexStatement stmt) {
        Table table = db.getTable(stmt.tableName);
        indexManager.createIndex(stmt.indexName, stmt.tableName, stmt.columnName, table);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(),
            "Index '" + stmt.indexName + "' created on " + stmt.tableName + "(" + stmt.columnName + ")");
    }

    // ---- DROP INDEX ----
    private QueryResult executeDropIndex(AstNode.DropIndexStatement stmt) {
        indexManager.dropIndex(stmt.indexName);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(),
            "Index '" + stmt.indexName + "' dropped");
    }

    // ---- DELETE ----
    private QueryResult executeDelete(AstNode.DeleteStatement stmt, String sessionId) {
        long txnId = txnManager.getCurrentTxn(sessionId);
        Table table = db.getTable(stmt.tableName);
        // Collect matching rows before deletion for WAL logging
        List<Map<String, Object>> toDelete = new ArrayList<>();
        for (Map<String, Object> row : table.getRows(txnId)) {
            if (stmt.where == null || isTruthy(evalExpr(stmt.where, row))) {
                toDelete.add(new LinkedHashMap<>(row));
            }
        }
        for (Map<String, Object> row : toDelete) {
            wal.append("DELETE", stmt.tableName, row);
        }
        int deleted = table.deleteRows(
            row -> stmt.where == null || isTruthy(evalExpr(stmt.where, row)),
            txnId
        );
        indexManager.invalidate(stmt.tableName);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(), deleted + " row(s) deleted");
    }

    // ---- UPDATE ----
    private QueryResult executeUpdate(AstNode.UpdateStatement stmt, String sessionId) {
        long txnId = txnManager.getCurrentTxn(sessionId);
        Table table = db.getTable(stmt.tableName);
        int updated = table.updateRows(
            row -> stmt.where == null || isTruthy(evalExpr(stmt.where, row)),
            row -> {
                for (AstNode.Assignment a : stmt.assignments)
                    row.put(a.column, evalExpr(a.value, row));
                // Log the updated row (post-mutation values)
                wal.append("UPDATE", stmt.tableName, new LinkedHashMap<>(row));
            },
            txnId
        );
        indexManager.invalidate(stmt.tableName);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(), updated + " row(s) updated");
    }

    // ---- Expression evaluator — Java 17: instanceof with cast, no pattern matching needed ----
    private Object evalExpr(AstNode node, Map<String, Object> row) {
        if (node instanceof AstNode.Literal)
            return ((AstNode.Literal) node).literalValue;

        if (node instanceof AstNode.ColumnRef) {
            AstNode.ColumnRef cr = (AstNode.ColumnRef) node;
            Object val = row.get(cr.column);
            if (val == null) val = row.get(cr.column.toLowerCase());
            return val;
        }

        if (node instanceof AstNode.FunctionCall)
            return evalFunction((AstNode.FunctionCall) node, row);

        if (node instanceof AstNode.BinaryExpr)
            return evalBinary((AstNode.BinaryExpr) node, row);

        if (node.type == AstNode.NodeType.UNARY_EXPR && "NOT".equals(node.value))
            return !isTruthy(evalExpr(node.children.get(0), row));

        if (node.type == AstNode.NodeType.ALIAS)
            return evalExpr(((AstNode.Alias) node).inner, row);

        return null;
    }

    private Object evalFunction(AstNode.FunctionCall fn, Map<String, Object> row) {
        if ("COUNT".equals(fn.funcName))  return 1L;
        if ("UPPER".equals(fn.funcName))  return fn.args.isEmpty() ? null : String.valueOf(evalExpr(fn.args.get(0), row)).toUpperCase();
        if ("LOWER".equals(fn.funcName))  return fn.args.isEmpty() ? null : String.valueOf(evalExpr(fn.args.get(0), row)).toLowerCase();
        if ("LENGTH".equals(fn.funcName)) return fn.args.isEmpty() ? null : (long) String.valueOf(evalExpr(fn.args.get(0), row)).length();
        if ("ABS".equals(fn.funcName))    return fn.args.isEmpty() ? null : Math.abs(toDouble(evalExpr(fn.args.get(0), row)));
        if ("ROUND".equals(fn.funcName))  return fn.args.isEmpty() ? null : (double) Math.round(toDouble(evalExpr(fn.args.get(0), row)));
        return null;
    }

    private Object evalBinary(AstNode.BinaryExpr be, Map<String, Object> row) {
        Object l = evalExpr(be.left, row);
        String op = be.operator;

        if ("AND".equals(op))  return isTruthy(l) && isTruthy(evalExpr(be.right, row));
        if ("OR".equals(op))   return isTruthy(l) || isTruthy(evalExpr(be.right, row));
        if ("=".equals(op))    return objectsEqual(l, evalExpr(be.right, row));
        if ("!=".equals(op) || "<>".equals(op)) return !objectsEqual(l, evalExpr(be.right, row));
        if ("<".equals(op))    return compare(l, evalExpr(be.right, row)) < 0;
        if (">".equals(op))    return compare(l, evalExpr(be.right, row)) > 0;
        if ("<=".equals(op))   return compare(l, evalExpr(be.right, row)) <= 0;
        if (">=".equals(op))   return compare(l, evalExpr(be.right, row)) >= 0;
        if ("+".equals(op))    return toDouble(l) + toDouble(evalExpr(be.right, row));
        if ("-".equals(op))    return toDouble(l) - toDouble(evalExpr(be.right, row));
        if ("*".equals(op))    return toDouble(l) * toDouble(evalExpr(be.right, row));
        if ("/".equals(op)) {
            double d = toDouble(evalExpr(be.right, row));
            return d == 0 ? null : toDouble(l) / d;
        }
        if ("LIKE".equals(op)) return likeMatch(String.valueOf(l), String.valueOf(evalExpr(be.right, row)));
        if ("IS".equals(op)) {
            Object r = evalExpr(be.right, row);
            return r == null ? l == null : objectsEqual(l, r);
        }
        if ("IN".equals(op)) {
            return be.right.children.stream().anyMatch(c -> objectsEqual(l, evalExpr(c, row)));
        }
        return null;
    }

    private boolean likeMatch(String val, String pattern) {
        String regex = "^" + pattern.replace("%", ".*").replace("_", ".") + "$";
        return val.matches(regex);
    }

    private boolean objectsEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number)
            return toDouble(a) == toDouble(b);
        return a.toString().equalsIgnoreCase(b.toString());
    }

    private int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number)
            return Double.compare(toDouble(a), toDouble(b));
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    @SuppressWarnings("unchecked")
    private Comparable<Object> toComparable(Object val) {
        if (val instanceof Number) return (Comparable<Object>) (Comparable<?>) toDouble(val);
        return val == null ? null : (Comparable<Object>) (Comparable<?>) val.toString();
    }

    private double toDouble(Object val) {
        if (val == null)           return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private boolean isTruthy(Object val) {
        if (val == null)            return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number)  return ((Number) val).doubleValue() != 0;
        return !val.toString().isEmpty();
    }

    private String resolveTableName(AstNode from) {
        if (from == null) throw new ExecutionException("No FROM clause");
        if (from.type == AstNode.NodeType.ALIAS) return resolveTableName(from.children.get(0));
        return from.value;
    }

    private TableStatsSummary summarizeStats(String tableName) {
        var statsOpt = statisticsManager.getStats(tableName);
        List<String> cols = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        if (statsOpt.isPresent()) {
            var tableStats = statsOpt.get();
            cols.add("column");
            cols.add("ndv");
            cols.add("min");
            cols.add("max");
            cols.add("row_count");
            for (var entry : tableStats.columns.entrySet()) {
                ColumnStats cs = entry.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("column", cs.columnName);
                row.put("ndv", cs.ndv);
                row.put("min", cs.minValue);
                row.put("max", cs.maxValue);
                row.put("row_count", cs.rowCount);
                rows.add(row);
            }
        }

        return new TableStatsSummary(cols, rows);
    }

    private static class TableStatsSummary {
        final List<String> columns;
        final List<Map<String, Object>> rows;
        TableStatsSummary(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    public static class ExecutionException extends RuntimeException {
        public ExecutionException(String msg) { super(msg); }
    }
}
