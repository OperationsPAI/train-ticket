package com.trainticket.platformkit.messaging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryConsumedEventStore implements ConsumedEventStore {
    private final ConcurrentMap<String, Set<String>> consumed = new ConcurrentHashMap<>();

    @Override
    public boolean alreadyConsumed(String consumerGroup, String eventId) {
        return consumed.computeIfAbsent(consumerGroup, ignored -> ConcurrentHashMap.newKeySet()).contains(eventId);
    }

    @Override
    public void recordConsumed(String consumerGroup, String eventId) {
        consumed.computeIfAbsent(consumerGroup, ignored -> ConcurrentHashMap.newKeySet()).add(eventId);
    }
}
