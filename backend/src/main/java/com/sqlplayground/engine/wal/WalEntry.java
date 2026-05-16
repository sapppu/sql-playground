package com.sqlplayground.engine.wal;

import java.io.Serializable;
import java.util.Map;

public class WalEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private long sequenceNumber;
    private String operation;
    private String tableName;
    private Map<String, Object> payload;

    public WalEntry() {
    }

    public WalEntry(long sequenceNumber, String operation, String tableName, Map<String, Object> payload) {
        this.sequenceNumber = sequenceNumber;
        this.operation = operation;
        this.tableName = tableName;
        this.payload = payload;
    }

    public long getSequenceNumber() { return sequenceNumber; }
    public String getOperation()    { return operation; }
    public String getTableName()    { return tableName; }
    public Map<String, Object> getPayload() { return payload; }
}
