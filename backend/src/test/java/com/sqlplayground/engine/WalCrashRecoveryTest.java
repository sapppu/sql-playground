package com.sqlplayground.engine;

import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.wal.WalEntry;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL crash-recovery demo as a repeatable test.
 *
 * <p>Run it on demand with:
 * <pre>
 *   mvn test -Dtest=WalCrashRecoveryTest
 * </pre>
 *
 * <p>Each test writes through a real file-backed WAL, then simulates a
 * crash by abandoning every in-memory reference <em>without</em> calling
 * {@code clear()} — the equivalent of {@code kill -9}, since every append
 * flushes to disk. A brand-new {@link WriteAheadLog} on the same file
 * then replays into a brand-new database, exactly like a restart.
 *
 * <p>Confirmed semantics for this engine:
 * <ul>
 *   <li>auto-commit INSERT/UPDATE/DELETE and DDL are WAL-logged on every
 *       append (with flush), so committed writes survive a crash;</li>
 *   <li>in-transaction writes are <em>never</em> appended
 *       (see QueryExecutor: {@code if (txnId == 0)}), living only in
 *       MVCC version chains, so uncommitted work is lost on crash and
 *       replay cannot resurrect it — no undo pass needed;</li>
 *   <li>known limitation: {@code COMMIT} only flips transaction status,
 *       it does not flush the transaction's writes to the WAL, so even
 *       committed-transaction writes are lost on crash today. The last
 *       test pins this behavior so a future fix has a failing-then-
 *       passing spec to aim at.</li>
 * </ul>
 */
class WalCrashRecoveryTest {

    @TempDir
    Path tempDir;

    /** File-backed WAL, as configured in a running server. */
    private WriteAheadLog fileWal(String name) throws Exception {
        WriteAheadLog wal = new WriteAheadLog();
        setField(wal, "walFilePath", tempDir.resolve(name).toString());
        setField(wal, "walEnabled", true);
        wal.init();
        return wal;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Map<String, Object> createTablePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tableName", "crashdemo");
        List<Map<String, Object>> cols = new ArrayList<>();
        cols.add(col("id", "INTEGER", true, false));
        cols.add(col("name", "VARCHAR", false, true));
        payload.put("columns", cols);
        return payload;
    }

    private static Map<String, Object> col(String name, String type, boolean pk, boolean nn) {
        Map<String, Object> cd = new LinkedHashMap<>();
        cd.put("name", name);
        cd.put("type", type);
        cd.put("primaryKey", pk);
        cd.put("notNull", nn);
        return cd;
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private static List<String> namesOf(InMemoryDatabase db) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : db.getTable("crashdemo").getRows(0L)) {
            names.add(String.valueOf(row.get("name")));
        }
        return names;
    }

    /** Fresh WAL instance over the same file + fresh DB, i.e. a restart. */
    private InMemoryDatabase restart(String walName) throws Exception {
        WriteAheadLog wal2 = fileWal(walName);
        InMemoryDatabase db2 = new InMemoryDatabase();
        wal2.replay(db2);
        return db2;
    }

    @Test
    void committedWritesSurviveCrash() throws Exception {
        WriteAheadLog wal1 = fileWal("crash.log");
        wal1.append("CREATE_TABLE", "crashdemo", createTablePayload());
        wal1.append("INSERT", "crashdemo", row(1L, "Ada"));
        wal1.append("INSERT", "crashdemo", row(2L, "Grace"));

        // crash: abandon wal1/db without clear() — nothing graceful runs.
        InMemoryDatabase db2 = restart("crash.log");

        assertTrue(db2.tableExists("crashdemo"));
        assertEquals(List.of("Ada", "Grace"), namesOf(db2));
    }

    @Test
    void uncommittedWritesDoNotSurviveCrash() throws Exception {
        WriteAheadLog wal1 = fileWal("crash.log");
        wal1.append("CREATE_TABLE", "crashdemo", createTablePayload());
        wal1.append("INSERT", "crashdemo", row(1L, "Ada"));

        // Exactly what the executor does for txnId != 0: version chain only,
        // no WAL append.
        InMemoryDatabase db1 = new InMemoryDatabase();
        wal1.replay(db1);
        TransactionManager tm = new TransactionManager();
        db1.getTable("crashdemo").setTransactionManager(tm);
        long txnId = tm.begin("demo-session");
        db1.getTable("crashdemo").insertRow(row(2L, "Uncommitted"), txnId);
        List<String> ownView = new ArrayList<>();
        for (Map<String, Object> row : db1.getTable("crashdemo").getRows(txnId)) {
            ownView.add(String.valueOf(row.get("name")));
        }
        assertTrue(ownView.contains("Uncommitted"), "sanity: txn sees its own write");

        // crash before COMMIT.
        InMemoryDatabase db2 = restart("crash.log");

        List<String> names = namesOf(db2);
        assertTrue(names.contains("Ada"), "committed write must survive");
        assertFalse(names.contains("Uncommitted"), "in-flight write must not survive");
        boolean leaked = wal1.getLog().stream()
            .anyMatch(e -> "Uncommitted".equals(String.valueOf(e.getPayload().get("name"))));
        assertFalse(leaked, "uncommitted row must never reach the in-memory WAL");
        String fileContent = java.nio.file.Files.readString(
            tempDir.resolve("crash.log"), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(fileContent.contains("Uncommitted"),
            "uncommitted row must never reach the WAL file");
    }

    @Test
    void committedTransactionWritesAreNotYetDurableAcrossRestart() throws Exception {
        WriteAheadLog wal1 = fileWal("crash.log");
        wal1.append("CREATE_TABLE", "crashdemo", createTablePayload());
        wal1.append("INSERT", "crashdemo", row(1L, "Ada"));

        InMemoryDatabase db1 = new InMemoryDatabase();
        wal1.replay(db1);
        TransactionManager tm = new TransactionManager();
        db1.getTable("crashdemo").setTransactionManager(tm);
        long txnId = tm.begin("demo-session");
        db1.getTable("crashdemo").insertRow(row(2L, "CommittedT btw"), txnId);
        tm.commit("demo-session");

        // crash after a fully committed transaction.
        InMemoryDatabase db2 = restart("crash.log");

        // KNOWN LIMITATION, pinned as spec for a future fix: COMMIT flips
        // status but never flushes the transaction's writes to the WAL,
        // so even committed-transaction work is lost on restart today.
        // (Auto-commit writes are unaffected — see committedWritesSurviveCrash.)
        assertFalse(namesOf(db2).contains("CommittedT btw"),
            "documents current gap: commit does not flush to WAL");
        assertTrue(namesOf(db2).contains("Ada"));
    }
}
