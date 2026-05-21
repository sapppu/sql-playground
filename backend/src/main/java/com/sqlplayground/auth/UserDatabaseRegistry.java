package com.sqlplayground.auth;

import com.sqlplayground.engine.wal.WalEntry;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user in-memory database registry.
 * On first access for a user, loads their WAL from disk and replays it
 * to reconstruct their tables and data.
 */
@Component
public class UserDatabaseRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserDatabaseRegistry.class);

    private final Map<String, InMemoryDatabase> registry = new ConcurrentHashMap<>();
    private final UserWalRegistry userWalRegistry;

    public UserDatabaseRegistry(UserWalRegistry userWalRegistry) {
        this.userWalRegistry = userWalRegistry;
    }

    public InMemoryDatabase getOrCreate(String username) {
        return registry.computeIfAbsent(username.toLowerCase(), u -> {
            InMemoryDatabase db = new InMemoryDatabase();

            // Replay user's WAL to restore their state
            UserWalRegistry.UserWal userWal = userWalRegistry.getOrCreate(u);
            List<WalEntry> entries = userWal.getLog();

            if (entries.isEmpty()) {
                // Fresh user — seed with sample data
                log.info("User '{}': no WAL found, seeding sample data.", u);
                db.seedSampleData();
            } else {
                log.info("User '{}': replaying {} WAL entries...", u, entries.size());
                replayWal(db, entries);
                log.info("User '{}': WAL replay complete. Tables: {}", u, db.getAllTables().keySet());
            }

            return db;
        });
    }

    /**
     * Replay WAL entries into the given database to restore tables and data.
     */
    private void replayWal(InMemoryDatabase db, List<WalEntry> entries) {
        List<WalEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(WalEntry::getSequenceNumber));

        for (WalEntry entry : sorted) {
            try {
                replayEntry(db, entry);
            } catch (Exception e) {
                log.warn("Skipping WAL entry seq={} op={} table={}: {}",
                    entry.getSequenceNumber(), entry.getOperation(),
                    entry.getTableName(), e.getMessage());
            }
        }
    }

    private void replayEntry(InMemoryDatabase db, WalEntry entry) {
        String op = entry.getOperation();
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

    public boolean hasDatabase(String username) {
        return registry.containsKey(username.toLowerCase());
    }

    public int activeUsers() { return registry.size(); }
}
