# SQL Playground — Custom Java Query Engine

A full-stack SQL playground backed by a hand-written Java SQL engine.
Zero external databases, no Docker, no cloud dependencies.

## Architecture

```
Browser (React + Vite :3000)
    │
    ├── POST /api/query   → Lexer → Parser → Planner → Executor
    ├── GET  /api/schema  → In-memory table catalog
    └── POST /api/schema/reset → Reseed sample data

Backend (Spring Boot :8081)
    ├── Lexer         — tokenises raw SQL
    ├── Parser        — recursive descent → AST
    ├── QueryPlanner  — cost-based execution plan tree
    ├── QueryExecutor — walks AST, evaluates against in-memory tables
    └── InMemoryDatabase — ConcurrentHashMap-backed table store
```

## Prerequisites (Ubuntu)

```bash
sudo apt update
sudo apt install openjdk-17-jdk maven nodejs npm
java -version    # should show 17
mvn -version
node --version   # should show 18+
```

## Run the backend

```bash
cd sql-playground/backend
mvn spring-boot:run
# Starts on http://localhost:8081
# Sample data (employees, departments, products) seeded automatically
```

## Run the frontend

```bash
cd sql-playground/frontend
npm install
npm run dev
# Opens on http://localhost:3000
# Sign up for a local account when prompted — the backend must be running
```

## Supported SQL

| Statement        | Example |
|-----------------|---------|
| SELECT           | `SELECT name, salary FROM employees WHERE salary > 80000` |
| WHERE            | `=  !=  <  >  <=  >=  AND  OR  NOT  LIKE  IS NULL  IN (...)` |
| ORDER BY         | `ORDER BY salary DESC, name ASC` |
| LIMIT / OFFSET   | `LIMIT 5 OFFSET 10` |
| GROUP BY + agg   | `SELECT dept, COUNT(*), AVG(salary) FROM employees GROUP BY dept` |
| DISTINCT         | `SELECT DISTINCT department FROM employees` |
| Functions        | `UPPER()  LOWER()  LENGTH()  ABS()  ROUND()  COUNT()  SUM()  AVG()  MAX()  MIN()` |
| CREATE TABLE     | `CREATE TABLE t (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL)` |
| INSERT           | `INSERT INTO t (name) VALUES ('Alice')` |
| UPDATE           | `UPDATE employees SET salary = 99000 WHERE name = 'Alice'` |
| DELETE           | `DELETE FROM employees WHERE active = false` |
| DROP TABLE       | `DROP TABLE students` |
| JOIN             | `SELECT employees.name, departments.budget FROM employees JOIN departments ON employees.department = departments.name` |
| Transactions     | `BEGIN` `COMMIT` `ROLLBACK` |
| CREATE/DROP INDEX| `CREATE INDEX idx_salary ON employees (salary)` |
| ANALYZE          | `ANALYZE employees` |

## Frontend features

- **Schema browser** — expandable table/column tree with types and PK/NN/IDX indicators
- **SQL editor** — Monaco editor with schema-aware autocomplete, Ctrl+Enter to run
- **Example queries** — one-click query bar with 8 sample statements
- **Results tab** — scrollable grid with type-aware cell colouring
- **Plan tab** — interactive execution plan tree (expandable nodes with cost stats, per-node stats drawer with estimated-vs-actual rows, join strategy badges)
- **Tokens tab** — colour-coded token stream from the Java lexer
- **Reset button** — restores all sample data in one click
- **Analyze all button** — recomputes table statistics for the planner

## Benchmark: nested_loop vs hash_join

Forced-strategy A/B on the same join (`bench_emp JOIN bench_dept ON department = name`), 5 timed plan+execute runs per cell after 2 warmups, medians reported. Strategies are forced via a bench-only planner override so the comparison is fair; both strategies assert identical result sets on every run. Raw data: [`backend/bench/join-benchmark.csv`](backend/bench/join-benchmark.csv) — regenerate with `mvn test -Dtest=JoinBenchmark -Dbench=1` from `backend/`.

![Join benchmark: hash_join vs nested_loop across table sizes](backend/bench/join-benchmark.svg)

| Employees | nested_loop | hash_join | Faster |
|----------:|------------:|----------:|--------|
| 1,000 | 5.83 ms | 3.79 ms | hash, 1.5x |
| 10,000 | 25.26 ms | 16.18 ms | hash, 1.6x |
| 100,000 | 295.59 ms | 78.33 ms | hash, 3.8x |

Varying the build side (2,000 employees, departments swept 10 → 2,000) shows why the planner's 200-row threshold is conservative rather than exact: hash_join leads at every measured size, from 6.7x at 10 build rows to ~1,100x (2,442 ms → 2.23 ms) at 2,000. No crossover was observed down to 10 rows — the threshold never picks a slower strategy in the measured range.

Environment: OpenJDK 17.0.20, 16-core Linux, default Maven/Surefire JVM flags. Numbers are machine-specific; rerun the command above to reproduce.

Resume line: reduced 100k-row join latency 74% (296 ms → 78 ms) by implementing a cost-based hash-join optimizer with NDV-driven cardinality estimates.

## Sample queries to try

```sql
-- Aggregation
SELECT department, COUNT(*), AVG(salary), MAX(salary)
FROM employees
GROUP BY department

-- Multi-condition filter
SELECT name, salary
FROM employees
WHERE salary > 80000 AND active = true
ORDER BY salary DESC

-- String functions
SELECT UPPER(name), LENGTH(name) FROM employees

-- Create and populate
CREATE TABLE courses (id INTEGER PRIMARY KEY, title VARCHAR NOT NULL, credits INTEGER)
INSERT INTO courses (title, credits) VALUES ('Algorithms', 4)
SELECT * FROM courses
```
