package com.trainticket.bookingorchestration.application;

import com.trainticket.bookingorchestration.domain.BookingSaga;
import java.util.Optional;

public interface BookingSagaRepository {
    Optional<BookingSaga> findById(String sagaId);
    Optional<BookingSaga> findByJourneyOrderId(String journeyOrderId);
    Optional<BookingSaga> findByCorrelationId(String correlationId);
    void save(BookingSaga saga, String correlationId);
}
