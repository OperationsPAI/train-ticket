package com.trainticket.bookingorchestration.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(ConsumedEventRepository.class)
public class InMemoryConsumedEventRepository implements ConsumedEventRepository {
    private final Set<String> eventIds = ConcurrentHashMap.newKeySet();
    @Override public boolean recordIfNew(String eventId, String stream) { return eventIds.add(eventId); }
}
