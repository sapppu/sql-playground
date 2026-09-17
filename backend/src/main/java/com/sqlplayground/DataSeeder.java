package com.sqlplayground;

import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WalEntry;
import com.sqlplayground.engine.wal.WriteAheadLog;
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
}
