package com.sqlplayground;

import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.storage.InMemoryDatabase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements ApplicationRunner {
    private final InMemoryDatabase db;
    private final StatisticsManager statisticsManager;
    public DataSeeder(InMemoryDatabase db, StatisticsManager statisticsManager) {
        this.db = db;
        this.statisticsManager = statisticsManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.seedSampleData();
        statisticsManager.analyzeAll(db);
    }
}
