package com.ielop.generator.model;

/** One sign-in of one user, closed by an explicit sign-out or by idle expiry. */
public final class Session {

    public enum EndReason { LOGOUT, EXPIRED }

    private final String id;
    private final User user;
    private final String ip;
    private final long startMillis;
    private long lastActivityMillis;

    public Session(String id, User user, String ip, long startMillis) {
        this.id = id;
        this.user = user;
        this.ip = ip;
        this.startMillis = startMillis;
        this.lastActivityMillis = startMillis;
    }

    public String id() {
        return id;
    }

    public User user() {
        return user;
    }

    public String ip() {
        return ip;
    }

    public long startMillis() {
        return startMillis;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public void touch(long millis) {
        lastActivityMillis = Math.max(lastActivityMillis, millis);
    }
}
