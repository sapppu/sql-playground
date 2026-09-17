package com.sqlplayground;

import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WalEntry;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final InMemoryDatabase db;
    private final WriteAheadLog wal;
    private final StatisticsManager statisticsManager;

    public DataSeeder(InMemoryDatabase db, WriteAheadLog wal, StatisticsManager statisticsManager) {
        this.db  = db;
        this.wal = wal;
        this.statisticsManager = statisticsManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<WalEntry> entries = wal.getLog();

        if (entries.isEmpty()) {
            log.info("WAL is empty — seeding fresh sample data.");
            db.seedSampleData();
            wal.checkpoint("initial-seed");
        } else {
            log.info("Replaying {} WAL entries to restore state...", entries.size());
            wal.replay(db);
            log.info("WAL replay complete. Tables restored: {}", db.getAllTables().keySet());
        }

        // Stats must exist before the first query plan is built so the
        // planner (selectivity, join strategy) works off real numbers.
        statisticsManager.analyzeAll(db);
        log.info("Statistics computed for tables: {}", db.getAllTables().keySet());
    }

    /**
     * Benchmark fixture: same schema shape and value vocabulary as the
     * sample seed (employee names, seed department names, salary band),
     * scaled to {@code empCount} employees against {@code deptCount}
     * departments (the first three are always the seed departments, so
     * every employee joins). Deterministic (fixed RNG seed) so runs are
     * reproducible. Creates {@code bench_emp} / {@code bench_dept}.
     */
    public static void seedScaleTables(InMemoryDatabase db, int empCount, int deptCount) {
        if (deptCount < 3) throw new IllegalArgumentException("deptCount must be >= 3");
        Table dept = new Table("bench_dept", Arrays.asList(
            new Table.Column("id",       "INTEGER", true,  true),
            new Table.Column("name",     "VARCHAR", false, true),
            new Table.Column("budget",   "DOUBLE",  false, false),
            new Table.Column("location", "VARCHAR", false, false)
        ));
        db.createTable(dept);
        String[][] seedDepts = {
            {"Engineering", "500000.0", "Floor 3"},
            {"Marketing",   "200000.0", "Floor 1"},
            {"HR",          "150000.0", "Floor 2"},
        };
        for (int i = 0; i < deptCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", (long) (i + 1));
            if (i < seedDepts.length) {
                row.put("name", seedDepts[i][0]);
                row.put("budget", Double.parseDouble(seedDepts[i][1]));
                row.put("location", seedDepts[i][2]);
            } else {
                row.put("name", "BulkDept" + i);
                row.put("budget", 1000.0);
                row.put("location", "Floor 9");
            }
            dept.insertRow(row);
        }

        Table emp = new Table("bench_emp", Arrays.asList(
            new Table.Column("id",         "INTEGER", true,  true),
            new Table.Column("name",       "VARCHAR", false, true),
            new Table.Column("department", "VARCHAR", false, false),
            new Table.Column("salary",     "DOUBLE",  false, false),
            new Table.Column("active",     "BOOLEAN", false, false)
        ));
        db.createTable(emp);
        String[] firstNames = {"Alice", "Bob", "Carol", "David", "Eve", "Frank",
                               "Grace", "Henry", "Ivy", "Jack", "Karen", "Leo"};
        String[] deptNames = {"Engineering", "Marketing", "HR"};
        Random rng = new Random(42);
        for (int i = 1; i <= empCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", (long) i);
            row.put("name", firstNames[i % firstNames.length] + "_" + i);
            row.put("department", deptNames[i % deptNames.length]);
            row.put("salary", 50000.0 + rng.nextDouble() * 70000.0);
            row.put("active", rng.nextBoolean());
            emp.insertRow(row);
        }
    }
}
