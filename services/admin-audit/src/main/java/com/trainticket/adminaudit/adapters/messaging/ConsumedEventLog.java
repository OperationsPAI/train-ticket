package com.trainticket.adminaudit.adapters.messaging;

public interface ConsumedEventLog {
    boolean recordIfNew(String eventId);

    default void discard(String eventId) {
        // A real Spring transaction rolls back the INSERT; in-memory logs override this.
    }

    default boolean alreadyProcessed(String eventId) {
        return !recordIfNew(eventId);
    }

    default void recordProcessed(String eventId) {
        recordIfNew(eventId);
    }
}
