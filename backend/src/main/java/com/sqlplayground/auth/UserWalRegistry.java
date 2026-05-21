package com.sqlplayground.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlplayground.engine.wal.WalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages per-user WAL files so each user's tables and data
 * persist across server restarts.
 *
 * WAL files stored at: {wal.user.dir}/{username}.wal.log
 */
@Component
public class UserWalRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserWalRegistry.class);

    @Value("${wal.user.dir:./data/wal}")
    private String walDir;

    private Path dirPath;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-user in-memory WAL + writer state */
    private final ConcurrentHashMap<String, UserWal> registry = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        dirPath = Paths.get(walDir);
        Files.createDirectories(dirPath);
        log.info("User WAL directory: {}", dirPath.toAbsolutePath());
    }

    /** Get or lazily create a UserWal for the given username. */
    public UserWal getOrCreate(String username) {
        return registry.computeIfAbsent(username.toLowerCase(), this::loadUserWal);
    }

    /** Load an existing user's WAL from disk, or create a new empty one. */
    private UserWal loadUserWal(String username) {
        Path walPath = dirPath.resolve(username + ".wal.log");
        UserWal userWal = new UserWal(walPath, mapper);
        userWal.loadFromDisk();
        return userWal;
    }

    /**
     * Represents a single user's WAL: in-memory log + file writer.
     */
    public static class UserWal {

        private final Path filePath;
        private final ObjectMapper mapper;
        private final CopyOnWriteArrayList<WalEntry> inMemoryLog = new CopyOnWriteArrayList<>();
        private final AtomicLong sequenceCounter = new AtomicLong(1);
        private final ReentrantLock writeLock = new ReentrantLock();
        private BufferedWriter fileWriter;

        UserWal(Path filePath, ObjectMapper mapper) {
            this.filePath = filePath;
            this.mapper = mapper;
        }

        void loadFromDisk() {
            if (!Files.exists(filePath)) return;
            try {
                List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                long maxSeq = 0;
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        WalEntry entry = mapper.readValue(line, WalEntry.class);
                        inMemoryLog.add(entry);
                        if (entry.getSequenceNumber() > maxSeq) maxSeq = entry.getSequenceNumber();
                    } catch (Exception e) {
                        // skip corrupt lines
                    }
                }
                sequenceCounter.set(maxSeq + 1);
            } catch (IOException e) {
                // ignore — fresh WAL
            }
        }

        private BufferedWriter getWriter() throws IOException {
            if (fileWriter == null) {
                fileWriter = Files.newBufferedWriter(
                    filePath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                );
            }
            return fileWriter;
        }

        public WalEntry append(String operation, String tableName, Map<String, Object> payload) {
            WalEntry entry = new WalEntry(
                sequenceCounter.getAndIncrement(),
                operation, tableName,
                new LinkedHashMap<>(payload)
            );
            writeLock.lock();
            try {
                String json = mapper.writeValueAsString(entry);
                getWriter().write(json);
                getWriter().newLine();
                getWriter().flush();
            } catch (IOException e) {
                // log but don't fail the operation
            } finally {
                writeLock.unlock();
            }
            inMemoryLog.add(entry);
            return entry;
        }

        public List<WalEntry> getLog() {
            return Collections.unmodifiableList(inMemoryLog);
        }

        public void clear() {
            writeLock.lock();
            try {
                inMemoryLog.clear();
                sequenceCounter.set(1);
                if (fileWriter != null) fileWriter.close();
                fileWriter = Files.newBufferedWriter(
                    filePath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (IOException e) {
                // ignore
            } finally {
                writeLock.unlock();
            }
        }

        public int size() { return inMemoryLog.size(); }
    }
}
