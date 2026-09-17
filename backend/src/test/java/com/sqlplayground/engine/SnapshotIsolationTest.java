package com.sqlplayground.engine;

import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.lexer.Lexer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot isolation (repeatable reads) across two interleaved sessions.
 *
 * <p>Driven through real SQL on distinct session IDs, exactly as the API
 * serves concurrent clients — no direct Table/TransactionManager poking.
 */
class SnapshotIsolationTest extends EngineTestBase {

    private QueryResult qs(String sql, String session) {
        AstNode ast = new Parser(new Lexer(sql).tokenize()).parse();
        return executor.execute(ast, session);
    }

    private int deptCount(String session) {
        return qs("SELECT * FROM departments", session).rows().size();
    }

    @Test
    void repeatableReadAcrossConcurrentCommit() {
        qs("BEGIN", "s1");
        assertEquals(3, deptCount("s1"));

        // Session 2 writes and commits while s1's transaction is open.
        qs("BEGIN", "s2");
        qs("INSERT INTO departments (name, budget, location) VALUES ('Legal', 90000, 'Floor 4')", "s2");
        qs("COMMIT", "s2");

        // s1's snapshot must not move...
        assertEquals(3, deptCount("s1"),
            "open transaction must keep seeing its begin snapshot");

        // ...but a fresh reader sees the committed row.
        assertEquals(4, deptCount(null),
            "auto-commit reads see latest committed state");
        qs("COMMIT", "s1");
    }

    @Test
    void ownWritesVisibleToSelfInvisibleToOthers() {
        qs("BEGIN", "s1");
        qs("INSERT INTO departments (name, budget, location) VALUES ('Legal', 90000, 'Floor 4')", "s1");
        assertEquals(4, deptCount("s1"), "own uncommitted write visible to self");

        qs("BEGIN", "s2");
        assertEquals(3, deptCount("s2"), "uncommitted write invisible to concurrent txn");
        assertEquals(3, deptCount(null), "uncommitted write invisible to auto-commit readers");
        qs("ROLLBACK", "s2");

        qs("COMMIT", "s1");
        assertEquals(4, deptCount(null), "committed write visible afterwards");
    }

    @Test
    void rollbackStaysInvisible() {
        qs("BEGIN", "s1");
        qs("INSERT INTO departments (name, budget, location) VALUES ('Legal', 90000, 'Floor 4')", "s1");
        qs("ROLLBACK", "s1");
        assertEquals(3, deptCount(null));
        assertEquals(3, deptCount("s1"), "rolled-back session reads clean state");
    }

    @Test
    void deleteCommittedAfterSnapshotStaysVisible() {
        qs("BEGIN", "s1");
        assertEquals(3, deptCount("s1"));

        // A concurrent transaction deletes and commits mid-snapshot.
        qs("BEGIN", "s2");
        qs("DELETE FROM departments WHERE name = 'HR'", "s2");
        qs("COMMIT", "s2");
        assertEquals(2, deptCount(null), "committed delete applies to fresh readers");

        assertEquals(3, deptCount("s1"),
            "row deleted after the snapshot must remain visible inside it");
        qs("COMMIT", "s1");
        assertEquals(2, deptCount(null));
    }

    @Test
    void ownDeleteHidesRowFromSelf() {
        qs("BEGIN", "s1");
        qs("DELETE FROM departments WHERE name = 'HR'", "s1");
        assertEquals(2, deptCount("s1"), "must not see a row deleted by self");
        assertEquals(3, deptCount(null), "uncommitted delete invisible to others");
        qs("ROLLBACK", "s1");
        assertEquals(3, deptCount(null));
    }
}
