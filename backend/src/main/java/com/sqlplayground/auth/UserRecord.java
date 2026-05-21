package com.sqlplayground.auth;

public class UserRecord {

    private String username;
    private String hashedPassword;
    private long createdAt;

    public UserRecord() {}

    public UserRecord(String username, String hashedPassword, long createdAt) {
        this.username       = username;
        this.hashedPassword = hashedPassword;
        this.createdAt      = createdAt;
    }

    public String getUsername()        { return username; }
    public String getHashedPassword()  { return hashedPassword; }
    public long getCreatedAt()         { return createdAt; }

    public void setUsername(String v)        { this.username = v; }
    public void setHashedPassword(String v)  { this.hashedPassword = v; }
    public void setCreatedAt(long v)         { this.createdAt = v; }
}
