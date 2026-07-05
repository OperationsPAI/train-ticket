package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ConsumedEventLog;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryConsumedEventLogRepository implements ConsumedEventLogRepository {
    private final ConcurrentMap<String, ConsumedEventLog> consumedEvents = new ConcurrentHashMap<>();

    @Override
    public boolean existsByEventId(String eventId) {
        return consumedEvents.containsKey(eventId);
    }

    @Override
    public void save(ConsumedEventLog log) {
        consumedEvents.put(log.eventId(), log);
    }
}
