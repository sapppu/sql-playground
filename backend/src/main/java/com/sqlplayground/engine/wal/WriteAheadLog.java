package com.sqlplayground.engine.wal;

import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WriteAheadLog {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    private final AtomicLong sequenceCounter = new AtomicLong(1);
    private final CopyOnWriteArrayList<WalEntry> walLog = new CopyOnWriteArrayList<>();

    public WalEntry append(String operation, String tableName, Map<String, Object> payload) {
        WalEntry entry = new WalEntry(
            sequenceCounter.getAndIncrement(),
            operation,
            tableName,
            new LinkedHashMap<>(payload)
        );
        walLog.add(entry);
        log.debug("WAL append: seq={} op={} table={}", entry.getSequenceNumber(), operation, tableName);
        return entry;
    }

    public List<WalEntry> getLog() {
        return Collections.unmodifiableList(walLog);
    }

    public void replay(InMemoryDatabase db) {
        log.info("Replaying WAL with {} entries", walLog.size());
        List<WalEntry> sorted = new ArrayList<>(walLog);
        sorted.sort(Comparator.comparingLong(WalEntry::getSequenceNumber));

        for (WalEntry entry : sorted) {
            try {
                Table table = db.getTable(entry.getTableName());
                switch (entry.getOperation()) {
                    case "INSERT":
                        table.insertRow(entry.getPayload());
                        break;
                    case "DELETE":
                        // Find and remove the row matching the payload's primary key
                        String pkColumn = findPrimaryKey(table);
                        if (pkColumn != null && entry.getPayload().containsKey(pkColumn)) {
                            Object pkValue = entry.getPayload().get(pkColumn);
                            table.deleteRows(row -> Objects.equals(row.get(pkColumn), pkValue));
                        }
                        break;
                    case "UPDATE":
                        // Find the row by PK and apply new values
                        String updatePk = findPrimaryKey(table);
                        if (updatePk != null && entry.getPayload().containsKey(updatePk)) {
                            Object pkVal = entry.getPayload().get(updatePk);
                            table.updateRows(
                                row -> Objects.equals(row.get(updatePk), pkVal),
                                row -> row.putAll(entry.getPayload())
                            );
                        }
                        break;
                    default:
                        log.warn("Unknown WAL operation: {}", entry.getOperation());
                }
            } catch (Exception e) {
                log.error("WAL replay failed for entry seq={}: {}", entry.getSequenceNumber(), e.getMessage());
            }
        }
        log.info("WAL replay complete");
    }

    public void clear() {
        walLog.clear();
        sequenceCounter.set(1);
    }

    private String findPrimaryKey(Table table) {
        for (Table.Column col : table.columns) {
            if (col.isPrimaryKey()) return col.getName();
        }
        return null;
    }
}
