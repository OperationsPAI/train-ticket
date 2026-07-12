package com.trainticket.postsales.application;

public interface ConsumedEventLog {
    boolean recordIfFirstSeen(String eventId);

    default void discard(String eventId) {
        // A real Spring transaction rolls back the INSERT; in-memory logs override this.
    }
}
