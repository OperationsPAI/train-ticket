package com.trainticket.payment.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConsumedEventDeduplicator {
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public boolean isProcessed(String eventId) {
        return processedEventIds.contains(eventId);
    }

    public void recordProcessed(String eventId) {
        processedEventIds.add(eventId);
    }

    public boolean recordIfNew(String eventId) {
        return processedEventIds.add(eventId);
    }
}
