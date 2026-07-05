package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ConsumedEventLog;
import java.util.HashSet;
import java.util.Set;

final class InMemoryConsumedEventLogRepository implements ConsumedEventLogRepository {
    private final Set<String> eventIds = new HashSet<>();
    int saveCount;

    @Override
    public boolean existsByEventId(String eventId) {
        return eventIds.contains(eventId);
    }

    @Override
    public void save(ConsumedEventLog log) {
        eventIds.add(log.eventId());
        saveCount++;
    }
}
