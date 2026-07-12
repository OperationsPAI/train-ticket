package com.trainticket.adminaudit.adapters.messaging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryConsumedEventLog implements ConsumedEventLog {
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean recordIfNew(String eventId) {
        return processed.add(eventId);
    }

    @Override
    public void discard(String eventId) {
        processed.remove(eventId);
    }
}
