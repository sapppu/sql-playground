package com.sqlplayground.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserStore {

    @Value("${user.store.path:./data/users.json}")
    private String storePath;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private Path path;

    @PostConstruct
    public void init() throws IOException {
        path = Paths.get(storePath);
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            List<UserRecord> loaded = mapper.readValue(
                path.toFile(),
                new TypeReference<List<UserRecord>>() {}
            );
            for (UserRecord u : loaded) users.put(u.getUsername(), u);
        }
        save();
    }

    public String signup(String username, String password) {
        if (username == null || username.isBlank())
            return "Username cannot be empty";
        if (username.length() < 3)
            return "Username must be at least 3 characters";
        if (!username.matches("[a-zA-Z0-9_]+"))
            return "Username can only contain letters, numbers, and underscores";
        if (password == null || password.length() < 6)
            return "Password must be at least 6 characters";
        if (users.containsKey(username.toLowerCase()))
            return "Username already taken";

        UserRecord record = new UserRecord(
            username.toLowerCase(),
            encoder.encode(password),
            System.currentTimeMillis()
        );
        users.put(record.getUsername(), record);
        save();
        return null;
    }

    public boolean verify(String username, String password) {
        UserRecord record = users.get(username.toLowerCase());
        if (record == null) return false;
        return encoder.matches(password, record.getHashedPassword());
    }

    public boolean exists(String username) {
        return users.containsKey(username.toLowerCase());
    }

    public int userCount() { return users.size(); }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                  .writeValue(path.toFile(), new ArrayList<>(users.values()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save user store", e);
        }
    }
}
