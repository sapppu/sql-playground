package com.sqlplayground.engine;

import com.sqlplayground.engine.executor.QueryExecutor;
import com.sqlplayground.engine.executor.QueryResult;
import com.sqlplayground.engine.index.IndexManager;
import com.sqlplayground.engine.lexer.Lexer;
import com.sqlplayground.engine.mvcc.TransactionManager;
import com.sqlplayground.engine.parser.AstNode;
import com.sqlplayground.engine.parser.Parser;
import com.sqlplayground.engine.stats.StatisticsManager;
import com.sqlplayground.engine.wal.WriteAheadLog;
import com.sqlplayground.storage.InMemoryDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared fixture: real engine stack over seeded sample data, no Spring needed.
 */
public abstract class EngineTestBase {

    protected InMemoryDatabase db;
    protected QueryExecutor executor;

    private static QueryResult run(QueryExecutor ex, String sql) {
        AstNode ast = new Parser(new Lexer(sql).tokenize()).parse();
        return ex.execute(ast);
    }

    protected QueryResult q(String sql) {
        return run(executor, sql);
    }

    @BeforeEach
    void setUpEngine() {
        db = new InMemoryDatabase();
        db.seedSampleData();
        executor = new QueryExecutor(
            db,
            new WriteAheadLog(),
            new IndexManager(),
            new TransactionManager(),
            new StatisticsManager()
        );
    }
}
