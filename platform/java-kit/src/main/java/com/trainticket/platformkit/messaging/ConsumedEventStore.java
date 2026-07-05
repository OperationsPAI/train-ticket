package com.trainticket.platformkit.messaging;

public interface ConsumedEventStore {
    boolean alreadyConsumed(String consumerGroup, String eventId);

    void recordConsumed(String consumerGroup, String eventId);
}
