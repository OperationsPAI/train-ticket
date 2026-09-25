package com.trainticket.platformkit.persistence;

public final class ReplayRetentionGapException extends RuntimeException {
    private final long requestedAfter;
    private final long oldestAvailable;

    public ReplayRetentionGapException(long requestedAfter, long oldestAvailable) {
        super("replay cursor " + requestedAfter + " precedes oldest retained sequence " + oldestAvailable);
        this.requestedAfter = requestedAfter;
        this.oldestAvailable = oldestAvailable;
    }

    public long requestedAfter() { return requestedAfter; }
    public long oldestAvailable() { return oldestAvailable; }
}
