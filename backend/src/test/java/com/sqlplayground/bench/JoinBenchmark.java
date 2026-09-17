package com.sqlplayground.bench;

import com.sqlplayground.DataSeeder;
import com.sqlplayground.engine.executor.QueryExecutor;
import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.planner.QueryPlanner;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.storage.InMemoryDatabase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Join strategy benchmark: nested_loop vs hash_join.
 *
 * <p>Two experiments, one run:
 * <ul>
 *   <li><b>scale</b> — departments fixed at 3 seed rows, employees grow
 *       ({@code bench.sizes}, default includes the 1k/10k/100k ladder).
 *       Shows how the gap widens with probe size.</li>
 *   <li><b>threshold</b> — employees fixed at {@code bench.probe} rows,
 *       departments grow across the planner's 200-row threshold
 *       ({@code bench.builds}, default 10,50,200,500,2000). Shows where
 *       hash_join actually overtakes nested_loop on the build side.</li>
 * </ul>
 *
 * <p>Does not run as part of the default suite (minutes-long by design).
 * Run explicitly with:
 * <pre>
 *   mvn test -Dtest=JoinBenchmark -Dbench=1
 * </pre>
 * Optional overrides: {@code -Dbench.sizes=1000,10000}
 * {@code -Dbench.builds=200,2000} {@code -Dbench.reps=3}
 * {@code -Dbench.warmup=1} {@code -Dbench.out=path/to.csv}.
 *
 * <p>Method: fresh DB per cell seeded deterministically via
 * {@link DataSeeder#seedScaleTables}, AST parsed once, then per strategy
 * {@code warmup} untimed runs followed by {@code reps} timed plan+execute
 * runs; the reported number is the median. Strategies are forced through
 * {@link QueryPlanner#forceStrategy} (cleared in a finally block) so the
 * comparison is a fair A/B the automatic planner could never produce.
 */
class JoinBenchmark {

    private static final String QUERY =
        "SELECT * FROM bench_emp JOIN bench_dept ON bench_emp.department = bench_dept.name";

    private static final String[] STRATEGIES = {"nested_loop", "hash_join"};

    private record Cell(String experiment, int empRows, int deptRows, String strategy,
                        double medianMs, double minMs, double maxMs, int reps) {
        String csvRow() {
            return String.join(",", experiment, String.valueOf(empRows), String.valueOf(deptRows),
                strategy, fmt(medianMs), fmt(minMs), fmt(maxMs), String.valueOf(reps));
        }

        String tableRow() {
            return empRows + " / " + deptRows + "\t" + strategy + "\t"
                + fmt(medianMs) + "\t" + fmt(minMs) + "\t" + fmt(maxMs);
        }

        private static String fmt(double v) {
            return String.format("%.2f", v);
        }
    }

    private static int[] intListProp(String key, String def) {
        String[] parts = System.getProperty(key, def).split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static int intProp(String key, int def) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double medianMs(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2) / 1_000_000.0;
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2_000_000.0;
    }

    /** Canonical signature of a result set for cross-strategy equality. */
    private static List<String> signature(QueryResult r) {
        List<String> sig = new ArrayList<>(r.rows().size());
        for (var row : r.rows()) sig.add(row.toString());
        Collections.sort(sig);
        return sig;
    }

    private final int reps = Math.max(1, intProp("bench.reps", 5));
    private final int warmup = Math.max(0, intProp("bench.warmup", 2));
    private final List<Cell> cells = new ArrayList<>();

    private void runCell(String experiment, int empCount, int deptCount) {
        InMemoryDatabase db = new InMemoryDatabase();
        DataSeeder.seedScaleTables(db, empCount, deptCount);
        StatisticsManager stats = new StatisticsManager();
        stats.analyzeAll(db);
        QueryPlanner planner = new QueryPlanner(db, new IndexManager(), stats);
        QueryExecutor executor = new QueryExecutor(db, new WriteAheadLog(),
            new IndexManager(), new TransactionManager(), stats);
        AstNode ast = new Parser(new Lexer(QUERY).tokenize()).parse();

        List<String> reference = null;
        for (String strategy : STRATEGIES) {
            QueryPlanner.forceStrategy(strategy);
            try {
                for (int w = 0; w < warmup; w++) {
                    planner.plan(ast);
                    executor.execute(ast);
                }
                List<Long> samples = new ArrayList<>(reps);
                List<String> sig = null;
                for (int r = 0; r < reps; r++) {
                    long start = System.nanoTime();
                    planner.plan(ast);
                    QueryResult result = executor.execute(ast);
                    samples.add(System.nanoTime() - start);
                    sig = signature(result);
                }
                if (reference == null) {
                    reference = sig;
                } else {
                    assertEquals(reference, sig,
                        "strategies disagree at emp=" + empCount + " dept=" + deptCount);
                }
                assertEquals(empCount, sig.size(),
                    "expected one output row per employee at emp=" + empCount);
                List<Long> sorted = new ArrayList<>(samples);
                Collections.sort(sorted);
                cells.add(new Cell(experiment, empCount, deptCount, strategy,
                    medianMs(samples),
                    Collections.min(samples) / 1_000_000.0,
                    Collections.max(samples) / 1_000_000.0, reps));
            } finally {
                QueryPlanner.clearForcedStrategy();
            }
        }
        System.out.println("[bench] finished emp=" + empCount + " dept=" + deptCount);
    }

    @Test
    void benchmarkJoinStrategies() throws IOException {
        assumeTrue(System.getProperty("bench") != null,
            "Skipped: rerun with -Dbench=1 (e.g. mvn test -Dtest=JoinBenchmark -Dbench=1)");

        for (int n : intListProp("bench.sizes", "10,25,50,100,200,500,1000,10000,100000")) {
            runCell("scale", n, 3);
        }
        int probe = intProp("bench.probe", 2000);
        for (int d : intListProp("bench.builds", "10,50,200,500,2000")) {
            runCell("threshold", probe, d);
        }

        StringBuilder csv = new StringBuilder(
            "experiment,emp_rows,dept_rows,strategy,median_ms,min_ms,max_ms,reps\n");
        StringBuilder table = new StringBuilder("\nexp\temp / dept\tstrategy\tmedian_ms\tmin_ms\tmax_ms\n");
        String lastExp = "";
        for (Cell c : cells) {
            if (!c.experiment().equals(lastExp)) {
                lastExp = c.experiment();
                table.append("--- ").append(lastExp).append(" ---\n");
            }
            csv.append(c.csvRow()).append('\n');
            table.append(c.tableRow()).append('\n');
        }

        Path out = Paths.get(System.getProperty("bench.out", "bench/join-benchmark.csv"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, csv.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(table);
        System.out.println("[bench] CSV written to " + out.toAbsolutePath());
    }
}
