package com.trainticket.adminaudit.adapters.messaging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryConsumedEventLog implements ConsumedEventLog {
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean alreadyProcessed(String eventId) {
        return processed.contains(eventId);
    }

    @Override
    public void recordProcessed(String eventId) {
        processed.add(eventId);
    }
}
