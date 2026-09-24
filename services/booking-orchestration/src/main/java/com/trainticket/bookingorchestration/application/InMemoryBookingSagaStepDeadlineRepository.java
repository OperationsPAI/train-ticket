package com.trainticket.bookingorchestration.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(BookingSagaStepDeadlineRepository.class)
public class InMemoryBookingSagaStepDeadlineRepository implements BookingSagaStepDeadlineRepository {
    private final ConcurrentHashMap<StepKey, Instant> deadlines = new ConcurrentHashMap<>();

    @Override
    public void schedule(String sagaId, String stepIdempotencyKey, Instant deadline) {
        deadlines.put(new StepKey(sagaId, stepIdempotencyKey), deadline);
    }

    @Override
    public void clear(String sagaId, String stepIdempotencyKey) {
        deadlines.remove(new StepKey(sagaId, stepIdempotencyKey));
    }

    @Override
    public void clearSaga(String sagaId) {
        deadlines.keySet().removeIf(key -> key.sagaId().equals(sagaId));
    }

    @Override
    public List<BookingSagaStepDeadline> findDue(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return deadlines.entrySet().stream()
            .filter(entry -> !entry.getValue().isAfter(now))
            .sorted(Comparator.comparing(entry -> entry.getValue()))
            .limit(limit)
            .map(entry -> new BookingSagaStepDeadline(entry.getKey().sagaId(), entry.getKey().stepIdempotencyKey(),
                entry.getValue()))
            .toList();
    }

    private record StepKey(String sagaId, String stepIdempotencyKey) {}
}
