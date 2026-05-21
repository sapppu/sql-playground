package com.sqlplayground.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class QueryHistoryStore {

    @Value("${query.history.path:./data/history}")
    private String historyDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private Path dirPath;

    @PostConstruct
    public void init() throws IOException {
        dirPath = Paths.get(historyDir);
        Files.createDirectories(dirPath);
    }

    public void record(String username, String sql, boolean success,
                       String message, int rowCount, long elapsedMs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp",  Instant.now().toEpochMilli());
        entry.put("sql",        sql);
        entry.put("success",    success);
        entry.put("message",    message);
        entry.put("rowCount",   rowCount);
        entry.put("elapsedMs",  elapsedMs);

        ReentrantLock lock = locks.computeIfAbsent(username, k -> new ReentrantLock());
        lock.lock();
        try {
            Path file = dirPath.resolve(username + ".ndjson");
            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(entry));
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("History write failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public List<Map<String, Object>> getHistory(String username, int limit) {
        Path file = dirPath.resolve(username + ".ndjson");
        if (!Files.exists(file)) return Collections.emptyList();

        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1;
                 i >= 0 && entries.size() < limit; i--) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    entries.add(mapper.readValue(line,
                        new TypeReference<Map<String, Object>>() {}));
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            System.err.println("History read failed: " + e.getMessage());
        }
        return entries;
    }

    public void clear(String username) {
        Path file = dirPath.resolve(username + ".ndjson");
        try { Files.deleteIfExists(file); }
        catch (IOException e) {
            System.err.println("History clear failed: " + e.getMessage());
        }
    }
}
