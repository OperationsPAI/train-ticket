package com.trainticket.bookingorchestration.application;

import java.time.Instant;
import java.util.List;

/**
 * Index of the saga steps that are waiting for a response.
 * The saga snapshot stays the source of truth for a step status and deadline, so a stale row here
 * can only delay a sweep, never change a step that already finished.
 * Rows exist so the overdue search reads a small indexed table instead of every saga snapshot.
 */
public interface BookingSagaStepDeadlineRepository {
    void schedule(String sagaId, String stepIdempotencyKey, Instant deadline);

    void clear(String sagaId, String stepIdempotencyKey);

    void clearSaga(String sagaId);

    List<BookingSagaStepDeadline> findDue(Instant now, int limit);

    record BookingSagaStepDeadline(String sagaId, String stepIdempotencyKey, Instant deadline) {
        public BookingSagaStepDeadline {
            if (sagaId == null || sagaId.isBlank()) {
                throw new IllegalArgumentException("sagaId is required");
            }
            if (stepIdempotencyKey == null || stepIdempotencyKey.isBlank()) {
                throw new IllegalArgumentException("stepIdempotencyKey is required");
            }
            if (deadline == null) {
                throw new IllegalArgumentException("deadline is required");
            }
        }
    }
}
