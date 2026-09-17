package com.sqlplayground.engine;

import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.model.Table;
import com.sqlplayground.storage.InMemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteAheadLog.replay() restores logged mutations into a database.
 */
class WriteAheadLogTest {

    private Table people() {
        return new Table("people", List.of(
            new Table.Column("id", "INTEGER", true, false),
            new Table.Column("name", "VARCHAR", false, false)
        ));
    }

    @Test
    void replayRestoresInserts() {
        WriteAheadLog wal = new WriteAheadLog();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("name", "Ada");
        wal.append("INSERT", "people", row);

        InMemoryDatabase db = new InMemoryDatabase();
        db.createTable(people());
        wal.replay(db);

        assertEquals(1, db.getTable("people").getRows().size());
        assertEquals("Ada", db.getTable("people").getRows().get(0).get("name"));
    }

    @Test
    void replaySkipsCheckpointAndUnknownOps() {
        WriteAheadLog wal = new WriteAheadLog();
        wal.checkpoint("test");
        wal.append("BOGUS_OP", "people", Map.of("id", 1L));

        InMemoryDatabase db = new InMemoryDatabase();
        db.createTable(people());
        assertDoesNotThrow(() -> wal.replay(db));
        assertTrue(db.getTable("people").getRows().isEmpty());
    }

    @Test
    void initCreatesMissingParentDirectories(@TempDir Path tempDir) throws Exception {
        WriteAheadLog wal = new WriteAheadLog();
        Path nested = tempDir.resolve("a").resolve("b").resolve("wal.log");
        Field pathField = WriteAheadLog.class.getDeclaredField("walFilePath");
        pathField.setAccessible(true);
        pathField.set(wal, nested.toString());
        Field enabledField = WriteAheadLog.class.getDeclaredField("walEnabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(wal, true);

        assertDoesNotThrow(wal::init);
        assertTrue(Files.exists(nested.getParent()));
        wal.append("CHECKPOINT", "_system", Map.of("label", "x"));
        assertTrue(Files.size(nested) > 0);
    }
}
