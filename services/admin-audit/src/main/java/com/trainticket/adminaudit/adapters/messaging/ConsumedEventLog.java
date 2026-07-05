package com.trainticket.adminaudit.adapters.messaging;

public interface ConsumedEventLog {
    boolean alreadyProcessed(String eventId);
    void recordProcessed(String eventId);
}
