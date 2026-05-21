package com.sqlplayground.engine.wal;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

public class WalEntry implements Serializable {

    private static final long serialVersionUID = 2L;

    private long sequenceNumber;
    private String operation;
    private String tableName;
    private Map<String, Object> payload;
    private long timestampMs;

    public WalEntry() {}

    public WalEntry(long sequenceNumber, String operation,
                    String tableName, Map<String, Object> payload) {
        this.sequenceNumber = sequenceNumber;
        this.operation      = operation;
        this.tableName      = tableName;
        this.payload        = payload;
        this.timestampMs    = Instant.now().toEpochMilli();
    }

    public long getSequenceNumber()       { return sequenceNumber; }
    public String getOperation()          { return operation; }
    public String getTableName()          { return tableName; }
    public Map<String, Object> getPayload(){ return payload; }
    public long getTimestampMs()          { return timestampMs; }

    public void setSequenceNumber(long v) { this.sequenceNumber = v; }
    public void setOperation(String v)    { this.operation = v; }
    public void setTableName(String v)    { this.tableName = v; }
    public void setPayload(Map<String, Object> v) { this.payload = v; }
    public void setTimestampMs(long v)    { this.timestampMs = v; }
}
