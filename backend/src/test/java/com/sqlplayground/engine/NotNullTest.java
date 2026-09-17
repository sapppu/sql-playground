package com.sqlplayground.engine;

import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #2: CREATE TABLE must accept NOT NULL (any order vs PRIMARY KEY,
 * plus explicit NULL), and INSERT/UPDATE must enforce it.
 */
class NotNullTest extends EngineTestBase {

    private AstNode.ColumnDef singleCol(String sql) {
        AstNode ast = new Parser(new Lexer(sql).tokenize()).parse();
        assertTrue(ast instanceof AstNode.CreateTableStatement);
        return ((AstNode.CreateTableStatement) ast).columnDefs.get(0);
    }

    @Test
    void parsesNotNullAfterType() {
        AstNode.ColumnDef def = singleCol("CREATE TABLE t (name VARCHAR NOT NULL)");
        assertTrue(def.notNull);
    }

    @Test
    void parsesNotNullInBothOrdersWithPrimaryKey() {
        AstNode.ColumnDef a = singleCol("CREATE TABLE t (id INTEGER PRIMARY KEY NOT NULL)");
        AstNode.ColumnDef b = singleCol("CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY)");
        assertTrue(a.notNull && a.primaryKey);
        assertTrue(b.notNull && b.primaryKey);
    }

    @Test
    void parsesExplicitNullAndBareColumn() {
        AstNode.ColumnDef nullable = singleCol("CREATE TABLE t (name VARCHAR NULL)");
        assertFalse(nullable.notNull);
        AstNode.ColumnDef bare = singleCol("CREATE TABLE t (name VARCHAR)");
        assertFalse(bare.notNull);
    }

    @Test
    void parsesSizedVarcharNotNull() {
        q("CREATE TABLE t (id INTEGER PRIMARY KEY, name VARCHAR(20) NOT NULL)");
    }

    @Test
    void insertNullIntoNotNullIsRejected() {
        q("CREATE TABLE t (id INTEGER PRIMARY KEY, name VARCHAR(20) NOT NULL)");
        Exception ex = assertThrows(RuntimeException.class,
            () -> q("INSERT INTO t (id, name) VALUES (1, NULL)"));
        assertTrue(ex.getMessage().contains("NOT NULL"));
        // Valid insert still works
        assertTrue(q("INSERT INTO t (id, name) VALUES (1, 'Ada')").message().contains("1 row"));
    }

    @Test
    void updateToNullIsRejected() {
        q("CREATE TABLE t (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL)");
        q("INSERT INTO t (id, name) VALUES (1, 'Ada')");
        Exception ex = assertThrows(RuntimeException.class,
            () -> q("UPDATE t SET name = NULL WHERE id = 1"));
        assertTrue(ex.getMessage().contains("NOT NULL"));
    }
}
