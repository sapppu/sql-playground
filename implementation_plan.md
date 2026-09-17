# SQL Playground — 5-Phase Engine Implementation Plan

Build a production-quality in-memory SQL engine by adding WAL, B-Tree indexes, JOINs, MVCC transactions, and a cost-based optimizer — each feature layered on the previous.

## Current Architecture Summary

The existing pipeline is: `Lexer` → `Parser` → `QueryPlanner` → `QueryExecutor`, backed by `InMemoryDatabase` (ConcurrentHashMap\<String, Table\>) and a `Table` model with `List<Map<String, Object>>` rows. Backend is Spring Boot 3.2 on port 8081, frontend is React + Vite on port 3000. Java 17, no external DB libraries.

---

## Phase 1 — Write-Ahead Log (WAL)

### New Files

#### [NEW] `engine/wal/WalEntry.java`
- Plain class with fields: `long sequenceNumber`, `String operation`, `String tableName`, `Map<String, Object> payload`
- Full constructor + no-arg constructor, implements `Serializable`

#### [NEW] `engine/wal/WriteAheadLog.java`
- `@Component`, holds `AtomicLong sequenceCounter` (starts at 1) and `CopyOnWriteArrayList<WalEntry> log`
- Methods: `append(op, table, payload)` → creates entry, adds to list, returns entry; `getLog()` → unmodifiable view; `replay(InMemoryDatabase db)` → re-applies entries (INSERT → `insertRow`, DELETE → `deleteRows` by PK, UPDATE → `updateRows` by PK); `clear()` for testing

### Modified Files

#### [MODIFY] [QueryExecutor.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/executor/QueryExecutor.java)
- Inject `WriteAheadLog wal` via constructor
- In `executeInsert`: call `wal.append("INSERT", tableName, row)` before `table.insertRow`
- In `executeDelete`: append WAL entry per deleted row with `"DELETE"` and row contents
- In `executeUpdate`: append WAL entry per updated row with `"UPDATE"` and new row values

> [!NOTE]
> For DELETE/UPDATE, we need to collect the affected rows *before* the mutation. For DELETE, we'll capture rows matching the predicate, append WAL entries, then delete. For UPDATE, we'll iterate matching rows, append WAL with new values, then mutate.

#### [MODIFY] [SqlPlaygroundApplication.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/SqlPlaygroundApplication.java)
- No changes needed — `DataSeeder` handles seeding via `ApplicationRunner`. WAL replay only matters for persistence (which is in-memory for now), so we skip replay during seed. No `ApplicationReadyEvent` listener needed unless we implement file-based WAL later.

#### [MODIFY] [SqlController.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/api/SqlController.java)
- Inject `WriteAheadLog wal` via constructor
- Add `GET /api/wal` returning `wal.getLog()` as JSON

#### [MODIFY] [App.jsx](file:///home/sappu/IdeaProjects/sql-playground/frontend/src/App.jsx)
- Add "WAL" sub-tab alongside results/plan/tokens
- After every query execution, fetch `/api/wal`
- Render a monospaced scrollable table: Seq | Op (color-coded: green=INSERT, red=DELETE, amber=UPDATE) | Table | Payload (JSON)
- As-built note: op colors follow the app theme instead — teal=INSERT, red=DELETE, maroon=UPDATE — decided deliberately for visual consistency with the teal/gold/red/maroon system.

---

## Phase 2 — B-Tree Index Engine

### New Files

#### [NEW] `engine/index/BTreeNode.java`
- Not a Spring component — plain POJO
- Fields: `boolean isLeaf`, `List<Comparable> keys`, `List<List<Map<String, Object>>> values` (leaf only), `List<BTreeNode> children` (internal only), `BTreeNode next` (leaf chaining)
- Constructor takes `boolean isLeaf`, min degree t=3 (max 5 keys per node)

#### [NEW] `engine/index/BTreeIndex.java`
- Holds root `BTreeNode` and `String columnName`
- `insert(Comparable key, Map<String, Object> row)` — splits root if full, calls `insertNonFull`
- `insertNonFull(node, key, row)` — recursive insertion
- `splitChild(parent, i, child)` — splits at median, promotes to parent
- `search(Comparable key)` — walks tree, returns rows with exact key match (or empty list)
- `rangeSearch(Comparable low, Comparable high)` — finds leaf for `low`, walks `next` chain collecting rows in [low, high]

#### [NEW] `engine/index/IndexManager.java`
- `@Component`, holds `Map<String, BTreeIndex> indexes` keyed by `"tableName.columnName"` (lowercase)
- `createIndex(indexName, tableName, columnName, table)` — builds BTreeIndex, iterates existing rows, inserts each
- `getIndex(tableName, columnName)` → `Optional<BTreeIndex>`
- `indexExists(tableName, columnName)` → boolean
- `invalidate(tableName)` — removes all indexes for that table
- `getIndexKeys()` — returns the set of index key strings for the API

### Modified Files

#### [MODIFY] [TokenType.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/TokenType.java)
- Add `INDEX` keyword token (JOIN, LEFT, INNER, ON already exist)

#### [MODIFY] [Lexer.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/Lexer.java)
- Add `KEYWORDS.put("INDEX", TokenType.INDEX)` in static block

#### [MODIFY] [AstNode.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/AstNode.java)
- Add `CREATE_INDEX_STMT`, `DROP_INDEX_STMT` to `NodeType` enum
- Add `CreateIndexStatement` inner class: `String indexName, String tableName, String columnName`
- Add `DropIndexStatement` inner class: `String indexName`

#### [MODIFY] [Parser.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/Parser.java)
- In `parseCreate()`: after `expect(CREATE)`, check if next token is `INDEX` (not `TABLE`). If INDEX, parse `CREATE INDEX indexName ON tableName (columnName)`
- In `parseDrop()`: after `expect(DROP)`, check if next is `INDEX`. If so, parse `DROP INDEX indexName`

#### [MODIFY] [QueryExecutor.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/executor/QueryExecutor.java)
- Inject `IndexManager indexManager` via constructor
- Handle `CREATE_INDEX_STMT`: call `indexManager.createIndex(...)`
- Handle `DROP_INDEX_STMT`: remove from index manager
- In `executeSelect`: check if WHERE is simple equality on indexed column → use `indexManager.getIndex().search(key)` for initial rowset
- After INSERT/DELETE/UPDATE: call `indexManager.invalidate(tableName)`

#### [MODIFY] [QueryPlanner.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/planner/QueryPlanner.java)
- Inject `IndexManager` via constructor
- In `planSelect`: after building SEQ_SCAN, check if WHERE has equality/range on indexed column → replace with `INDEX_SCAN` plan node

#### [MODIFY] [SqlController.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/api/SqlController.java)
- Add `GET /api/indexes` returning index keys list

#### [MODIFY] [App.jsx](file:///home/sappu/IdeaProjects/sql-playground/frontend/src/App.jsx)
- Fetch `/api/indexes` alongside schema
- Show ⚡ lightning bolt next to indexed columns in schema browser
- As-built note: indexed columns show an IDX text badge (same treatment as the PK badge) instead of the ⚡ glyph, per the frontend theme decision; IDX/plan-badge styling is shared.
- Add `INDEX_SCAN` to `OP_COLORS` (teal/cyan)
- Add example queries: `CREATE INDEX idx_salary ON employees (salary)`, `SELECT * FROM employees WHERE salary = 95000`

---

## Phase 3 — JOIN Execution (Hash Join + Nested Loop)

### Modified Files

#### [MODIFY] [AstNode.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/AstNode.java)
- Add `JoinClause` inner class: `String rightTable, String joinType, AstNode onCondition`
- Add `List<JoinClause> joins` field to `SelectStatement`
- Update `SelectStatement` constructor to accept joins parameter

#### [MODIFY] [Parser.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/Parser.java)
- In `parseSelect()`: after parsing FROM table ref, loop to check for `JOIN` / `INNER JOIN` / `LEFT JOIN` tokens
- For each join: parse right table name, `ON` keyword, and condition expression
- Build `JoinClause` and collect into list, pass to `SelectStatement`

#### [MODIFY] [QueryExecutor.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/executor/QueryExecutor.java)
- Add `nestedLoopJoin(left, rightTable, onCondition, joinType)` method
- Add `hashJoin(left, rightTable, onCondition, joinType)` method
- In `executeSelect`: if `stmt.joins` is non-empty, execute joins using threshold (200 rows → nested loop vs hash join)
- Merge rows with qualified column names on collision
- Handle LEFT JOIN null-padding for unmatched left rows

#### [MODIFY] [QueryPlanner.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/planner/QueryPlanner.java)
- When joins exist: add `NESTED_LOOP_JOIN` or `HASH_JOIN` plan node based on right table size threshold (200)
- Stats: `join_type`, `left_table`, `right_table`, `strategy`, `estimated_rows`

#### [MODIFY] [App.jsx](file:///home/sappu/IdeaProjects/sql-playground/frontend/src/App.jsx)
- Add JOIN example queries
- When plan node is `HASH_JOIN` or `NESTED_LOOP_JOIN`, show strategy in a teal badge
- Add `HASH_JOIN` and `NESTED_LOOP_JOIN` to `OP_COLORS`

---

## Phase 4 — MVCC (Multi-Version Concurrency Control)

### New Files

#### [NEW] `engine/mvcc/RowVersion.java`
- Fields: `long txnId`, `Map<String, Object> data`, `boolean deleted`, `long createdByTxn`, `long deletedByTxn` (0 = not deleted)
- Constructor takes `txnId` and `data`

#### [NEW] `engine/mvcc/TransactionManager.java`
- `@Component`, holds `AtomicLong txnCounter`, `ConcurrentHashMap<Long, String> activeTxns`, `ThreadLocal<Long> currentTxn`
- `begin()` → generate ID, mark ACTIVE, set ThreadLocal, return ID
- `commit(txnId)` → mark COMMITTED, clear ThreadLocal
- `rollback(txnId)` → mark ROLLED_BACK, clear ThreadLocal, call `undoTransaction(txnId)`
- `getCurrentTxn()` → ThreadLocal value or 0
- `isVisible(RowVersion rv, long readerTxnId)` → visibility check based on `createdByTxn`/`deletedByTxn`
- `undoTransaction(txnId)` → iterates all tables, reverts versions created/deleted by this txn

> [!IMPORTANT]
> MVCC adds a `versionChains` layer to Table but keeps the existing `rows` list working in auto-commit mode (when `getCurrentTxn()` returns 0). This ensures backward compatibility — all existing behavior remains identical.

### Modified Files

#### [MODIFY] [TokenType.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/TokenType.java)
- Add `BEGIN`, `COMMIT`, `ROLLBACK` keyword tokens

#### [MODIFY] [Lexer.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/Lexer.java)
- Add keyword mappings for `BEGIN`, `COMMIT`, `ROLLBACK`

#### [MODIFY] [AstNode.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/AstNode.java)
- Add `BEGIN_STMT`, `COMMIT_STMT`, `ROLLBACK_STMT` to `NodeType`
- Add corresponding statement classes (no fields needed)

#### [MODIFY] [Parser.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/Parser.java)
- In `parseStatement()`: check for `BEGIN`, `COMMIT`, `ROLLBACK` tokens

#### [MODIFY] [Table.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/model/Table.java)
- Add `List<List<RowVersion>> versionChains` field
- When `TransactionManager.getCurrentTxn()` is 0: behave exactly as today (auto-commit)
- When non-zero: reads filter through `isVisible`, inserts create `RowVersion`, deletes set `deletedByTxn`

#### [MODIFY] [QueryExecutor.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/executor/QueryExecutor.java)
- Inject `TransactionManager`
- Handle `BEGIN_STMT` → `txnManager.begin()`, return message
- Handle `COMMIT_STMT` → `txnManager.commit(currentTxn)`
- Handle `ROLLBACK_STMT` → `txnManager.rollback(currentTxn)`

#### [MODIFY] [SqlController.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/api/SqlController.java)
- Add `GET /api/transactions` endpoint

#### [MODIFY] [App.jsx](file:///home/sappu/IdeaProjects/sql-playground/frontend/src/App.jsx)
- Transaction status bar between toolbar and editor: "Auto-commit" (grey) / "TXN #N active" (amber)
- As-built note: the pill reads "TXN #N active" with the real transaction id, but in teal (active/healthy) rather than amber, per the color-meaning system.
- BEGIN / COMMIT / ROLLBACK buttons

---

## Phase 5 — Cost-Based Optimizer with Statistics

### New Files

#### [NEW] `engine/stats/ColumnStats.java`
- Fields: `columnName`, `rowCount`, `ndv`, `minValue`, `maxValue`, `List<Object> histogramBuckets` (10 samples)
- Static factory `compute(columnName, rows)` — collects distinct values, picks 10 evenly-spaced samples

#### [NEW] `engine/stats/TableStats.java`
- Fields: `tableName`, `rowCount`, `Map<String, ColumnStats> columns`
- Static factory `compute(tableName, table)`

#### [NEW] `engine/stats/StatisticsManager.java`
- `@Component`, holds `ConcurrentHashMap<String, TableStats> statsCache`
- `analyze(tableName, table)` → compute and cache
- `getStats(tableName)` → `Optional<TableStats>`
- `estimateSelectivity(tableName, columnName, operator, literal)` → double [0,1]
  - Equality: `1.0 / ndv`
  - Range: histogram-based estimation
  - LIKE: flat 0.1
  - Fallback: 0.3
- `analyzeAll(InMemoryDatabase db)` → iterates all tables

### Modified Files

#### [MODIFY] [TokenType.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/TokenType.java)
- Add `ANALYZE` keyword token

#### [MODIFY] [Lexer.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/lexer/Lexer.java)
- Add `ANALYZE` keyword mapping

#### [MODIFY] [AstNode.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/AstNode.java)
- Add `ANALYZE_STMT` to `NodeType`
- Add `AnalyzeStatement` inner class with `String tableName`

#### [MODIFY] [Parser.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/parser/Parser.java)
- In `parseStatement()`: check for `ANALYZE` token, parse `ANALYZE tableName`

#### [MODIFY] [QueryPlanner.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/planner/QueryPlanner.java)
- Inject `StatisticsManager`
- Replace hard-coded 0.3 selectivity with `estimateSelectivity()` calls
- For JOINs: compare table stats to pick build vs probe side (smaller table = build side)
- Predicate pushdown annotation in plan stats

#### [MODIFY] [QueryExecutor.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/engine/executor/QueryExecutor.java)
- Handle `ANALYZE_STMT` → call `statisticsManager.analyze()`, return NDV/row-count summary

#### [MODIFY] [DataSeeder.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/DataSeeder.java)
- Inject `StatisticsManager`, call `analyzeAll(db)` after seeding

#### [MODIFY] [SqlController.java](file:///home/sappu/IdeaProjects/sql-playground/backend/src/main/java/com/sqlplayground/api/SqlController.java)
- Add `GET /api/stats` returning full stats cache
- Add `POST /api/stats/analyze-all` triggering full re-analysis

#### [MODIFY] [App.jsx](file:///home/sappu/IdeaProjects/sql-playground/frontend/src/App.jsx)
- Stats drawer on each plan node: estimated rows, selectivity, cost, build/probe side
- "Analyze All" button next to Reset
- `ANALYZE employees` as an example query

---

## Verification Plan

### Automated Tests (after each phase)

1. **Phase 1**: `mvn compile` → Run `INSERT INTO employees (name) VALUES ('Zara')` → GET `/api/wal` → verify WAL entry
2. **Phase 2**: `mvn compile` → `CREATE INDEX idx_salary ON employees (salary)` → `SELECT * FROM employees WHERE salary = 95000` → verify plan shows `INDEX_SCAN`
3. **Phase 3**: `mvn compile` → `SELECT employees.name, departments.name FROM employees JOIN departments ON employees.department = departments.name` → verify joined results
4. **Phase 4**: `mvn compile` → `BEGIN` → `INSERT INTO employees (name) VALUES ('TestUser')` → `SELECT * FROM employees WHERE name = 'TestUser'` → `ROLLBACK` → SELECT again → verify 0 rows
5. **Phase 5**: `mvn compile` → `ANALYZE employees` → `SELECT * FROM employees WHERE salary = 95000` → verify plan shows selectivity ~0.125

### Manual Verification
- Browser test on http://localhost:3000 after each phase to verify frontend additions visually

---

## Open Questions

> [!IMPORTANT]
> **Thread safety for MVCC**: The spec mentions `ThreadLocal<Long>` for `currentTxn`. Since Spring Boot handles each HTTP request on a separate thread, each request starts with no active transaction. This means transactions are single-request scoped by default. Is that acceptable, or should we add session-based transaction tracking (e.g., via a transaction ID header from the frontend)? **I'll implement the ThreadLocal approach as specified, but note it means BEGIN + INSERT + COMMIT must happen within a single request or we need session tracking.**

> [!NOTE]
> **Resolution**: I'll use a `ConcurrentHashMap<String, Long>` to track sessionId→txnId mappings so the frontend can maintain a transaction across multiple requests. Each request will include a session header.

> [!IMPORTANT]
> **DELETE WAL entries**: The spec says to append a WAL entry per deleted row in `executeDelete`. Currently, `Table.deleteRows()` removes rows in-place using `removeIf`. To capture the rows *before* deletion, I'll iterate first to collect matching rows, append WAL entries, then call `deleteRows`. This is a slight refactor of the delete flow in QueryExecutor.

> [!NOTE]
> **UPDATE WAL entries**: Same pattern — collect matching rows, compute new values, append WAL entries, then mutate. The existing `updateRows` method mutates in-place, so we'll need to capture pre-mutation state.
