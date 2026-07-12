package com.trainticket.bookingorchestration.application;

import com.trainticket.bookingorchestration.domain.BookingSaga;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(BookingSagaRepository.class)
public class InMemoryBookingSagaRepository implements BookingSagaRepository {
    private final ConcurrentHashMap<String, BookingSaga> sagas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> correlationIdToSaga = new ConcurrentHashMap<>();

    @Override
    public Optional<BookingSaga> findById(String sagaId) {
        return Optional.ofNullable(sagas.get(sagaId));
    }

    @Override
    public Optional<BookingSaga> findByJourneyOrderId(String journeyOrderId) {
        return sagas.values().stream()
            .filter(saga -> saga.journeyOrderId().equals(journeyOrderId))
            .findFirst();
    }

    @Override
    public Optional<BookingSaga> findByCorrelationId(String correlationId) {
        return Optional.ofNullable(correlationIdToSaga.get(correlationId)).map(sagas::get);
    }

    @Override
    public void save(BookingSaga saga, String correlationId) {
        sagas.put(saga.sagaId(), saga);
        if (correlationId != null && !correlationId.isBlank()) {
            correlationIdToSaga.putIfAbsent(correlationId, saga.sagaId());
        }
    }
}
