package com.trainticket.postsales.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryConsumedEventLog implements ConsumedEventLog {
    private final Set<String> consumedEventIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean recordIfFirstSeen(String eventId) {
        return consumedEventIds.add(eventId);
    }
}
