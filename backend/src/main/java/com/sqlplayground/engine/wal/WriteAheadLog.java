package com.sqlplayground.engine.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class WriteAheadLog {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    @Value("${wal.file.path:./wal.log}")
    private String walFilePath;

    @Value("${wal.enabled:true}")
    private boolean walEnabled;

    private final AtomicLong sequenceCounter = new AtomicLong(1);
    private final CopyOnWriteArrayList<WalEntry> inMemoryLog = new CopyOnWriteArrayList<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path walPath;
    private BufferedWriter fileWriter;

    @PostConstruct
    public void init() throws IOException {
        walPath = Paths.get(walFilePath);
        log.info("WAL file path: {}", walPath.toAbsolutePath());

        if (Files.exists(walPath)) {
            loadExistingEntries();
        }

        if (walEnabled) {
            fileWriter = Files.newBufferedWriter(
                walPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            log.info("WAL opened for append. Existing entries: {}", inMemoryLog.size());
        }
    }

    private void loadExistingEntries() {
        try {
            List<String> lines = Files.readAllLines(walPath, StandardCharsets.UTF_8);
            long maxSeq = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    WalEntry entry = objectMapper.readValue(line, WalEntry.class);
                    inMemoryLog.add(entry);
                    if (entry.getSequenceNumber() > maxSeq) maxSeq = entry.getSequenceNumber();
                } catch (Exception e) {
                    log.warn("Skipping corrupt WAL line: {}", line);
                }
            }
            sequenceCounter.set(maxSeq + 1);
            log.info("Loaded {} WAL entries from disk. Next seq: {}", inMemoryLog.size(), maxSeq + 1);
        } catch (IOException e) {
            log.error("Failed to read WAL file: {}", e.getMessage());
        }
    }

    public WalEntry append(String operation, String tableName, Map<String, Object> payload) {
        WalEntry entry = new WalEntry(
            sequenceCounter.getAndIncrement(),
            operation,
            tableName,
            new LinkedHashMap<>(payload)
        );

        if (walEnabled && fileWriter != null) {
            writeLock.lock();
            try {
                String json = objectMapper.writeValueAsString(entry);
                fileWriter.write(json);
                fileWriter.newLine();
                fileWriter.flush();
            } catch (IOException e) {
                log.error("WAL write failed for seq {}: {}", entry.getSequenceNumber(), e.getMessage());
            } finally {
                writeLock.unlock();
            }
        }

        inMemoryLog.add(entry);
        log.debug("WAL append: seq={} op={} table={}", entry.getSequenceNumber(), operation, tableName);
        return entry;
    }

    public List<WalEntry> getLog() {
        return Collections.unmodifiableList(inMemoryLog);
    }

    public List<WalEntry> getLogForTable(String tableName) {
        List<WalEntry> result = new ArrayList<>();
        for (WalEntry e : inMemoryLog) {
            if (tableName.equalsIgnoreCase(e.getTableName())) result.add(e);
        }
        return result;
    }

    public void clear() {
        writeLock.lock();
        try {
            inMemoryLog.clear();
            sequenceCounter.set(1);
            if (walEnabled && walPath != null) {
                if (fileWriter != null) {
                    fileWriter.close();
                }
                fileWriter = Files.newBufferedWriter(
                    walPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
                log.info("WAL cleared and file truncated.");
            }
        } catch (IOException e) {
            log.error("WAL clear failed: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void checkpoint(String label) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("timestamp", System.currentTimeMillis());
        append("CHECKPOINT", "_system", payload);
        log.info("WAL checkpoint: {}", label);
    }

    public long getNextSequence() { return sequenceCounter.get(); }
    public int size()             { return inMemoryLog.size(); }
}
