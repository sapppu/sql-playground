package com.sqlplayground.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserStore userStore;
    private final JwtUtil jwtUtil;

    public AuthController(UserStore userStore, JwtUtil jwtUtil) {
        this.userStore = userStore;
        this.jwtUtil   = jwtUtil;
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(
            @RequestBody Map<String, String> req) {

        String username = req.get("username");
        String password = req.get("password");

        String error = userStore.signup(username, password);
        if (error != null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("error", error);
            return ResponseEntity.badRequest().body(resp);
        }

        String token = jwtUtil.generate(username.toLowerCase());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",  true);
        resp.put("token",    token);
        resp.put("username", username.toLowerCase());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> req) {

        String username = req.get("username");
        String password = req.get("password");

        if (!userStore.verify(username, password)) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("error",   "Invalid username or password");
            return ResponseEntity.status(401).body(resp);
        }

        String token = jwtUtil.generate(username.toLowerCase());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",  true);
        resp.put("token",    token);
        resp.put("username", username.toLowerCase());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        String username = resolveUser(auth);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (username == null) {
            resp.put("authenticated", false);
            return ResponseEntity.status(401).body(resp);
        }
        resp.put("authenticated", true);
        resp.put("username", username);
        return ResponseEntity.ok(resp);
    }

    private String resolveUser(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return jwtUtil.validate(auth.substring(7));
    }
}
