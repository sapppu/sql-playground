package com.sqlplayground;

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

    public DataSeeder(InMemoryDatabase db, WriteAheadLog wal) {
        this.db  = db;
        this.wal = wal;
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
            replayWal(entries);
            log.info("WAL replay complete. Tables restored: {}", db.getAllTables().keySet());
        }
    }

    private void replayWal(List<WalEntry> entries) {
        List<WalEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(WalEntry::getSequenceNumber));

        for (WalEntry entry : sorted) {
            try {
                replayEntry(entry);
            } catch (Exception e) {
                log.warn("Skipping WAL entry seq={} op={} table={}: {}",
                    entry.getSequenceNumber(), entry.getOperation(),
                    entry.getTableName(), e.getMessage());
            }
        }
    }

    private void replayEntry(WalEntry entry) {
        String op    = entry.getOperation();
        String table = entry.getTableName();
        Map<String, Object> payload = entry.getPayload();

        if ("CHECKPOINT".equals(op)) return;

        if ("CREATE_TABLE".equals(op)) {
            if (!db.tableExists(table)) {
                List<Table.Column> cols = new ArrayList<>();
                Object colDefs = payload.get("columns");
                if (colDefs instanceof List) {
                    for (Object colDef : (List<?>) colDefs) {
                        if (colDef instanceof Map) {
                            Map<?, ?> cd = (Map<?, ?>) colDef;
                            cols.add(new Table.Column(
                                String.valueOf(cd.get("name")),
                                String.valueOf(cd.get("type")),
                                Boolean.TRUE.equals(cd.get("primaryKey")),
                                Boolean.TRUE.equals(cd.get("notNull"))
                            ));
                        }
                    }
                }
                if (!cols.isEmpty()) db.createTable(new Table(table, cols));
            }
            return;
        }

        if ("DROP_TABLE".equals(op)) {
            if (db.tableExists(table)) db.dropTable(table);
            return;
        }

        if ("INSERT".equals(op)) {
            if (db.tableExists(table)) {
                db.getTable(table).insertRow(payload, 0L);
            }
            return;
        }

        if ("DELETE".equals(op)) {
            if (db.tableExists(table)) {
                Object pkVal = payload.get("id");
                db.getTable(table).deleteRows(
                    row -> pkVal != null && pkVal.toString().equals(
                        row.getOrDefault("id", "").toString()),
                    0L
                );
            }
            return;
        }

        if ("UPDATE".equals(op)) {
            if (db.tableExists(table)) {
                Object pkVal = payload.get("id");
                db.getTable(table).updateRows(
                    row -> pkVal != null && pkVal.toString().equals(
                        row.getOrDefault("id", "").toString()),
                    row -> row.putAll(payload),
                    0L
                );
            }
        }
    }
}
