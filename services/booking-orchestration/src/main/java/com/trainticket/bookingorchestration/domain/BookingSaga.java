package com.trainticket.bookingorchestration.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaAdvanced;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaCompleted;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaFailed;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaManualReviewRequired;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaRetryScheduled;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaStarted;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaStepFailed;
import static com.trainticket.bookingorchestration.domain.BookingEvent.BookingSagaStepSucceeded;

public final class BookingSaga {
    private final String sagaId;
    private final String journeyOrderId;
    private final String idempotencyKey;
    private final EventRecorder eventRecorder;
    private final Map<String, BookingSagaStep> stepsByIdempotencyKey = new LinkedHashMap<>();
    private BookingSagaStatus status = BookingSagaStatus.PLANNED;
    private String terminalReason;

    private BookingSaga(String sagaId, String journeyOrderId, String idempotencyKey, Clock clock) {
        this.sagaId = requireText(sagaId, "sagaId");
        this.journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.eventRecorder = new EventRecorder(clock);
    }

    public static BookingSaga start(String sagaId, String journeyOrderId, String orderVersion, String bookingPurpose,
                                    List<BookingSagaStep> plan, Clock clock) {
        String idempotencyKey = journeyOrderId + ":" + orderVersion + ":" + bookingPurpose;
        BookingSaga saga = new BookingSaga(sagaId, journeyOrderId, idempotencyKey, clock);
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("saga plan must contain at least one step");
        }
        for (BookingSagaStep step : plan) {
            saga.addStep(step);
        }
        saga.status = BookingSagaStatus.RESERVING;
        saga.record(new BookingSagaStarted(saga.nextEventId(), saga.sagaId, saga.now(), saga.journeyOrderId));
        saga.record(new BookingSagaAdvanced(saga.nextEventId(), saga.sagaId, saga.now(), saga.status));
        return saga;
    }

    public String sagaId() {
        return sagaId;
    }

    public String journeyOrderId() {
        return journeyOrderId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public BookingSagaStatus status() {
        return status;
    }

    public Optional<String> terminalReason() {
        return Optional.ofNullable(terminalReason);
    }

    public Collection<BookingSagaStep> steps() {
        return List.copyOf(stepsByIdempotencyKey.values());
    }

    public BookingSagaStep step(String idempotencyKey) {
        BookingSagaStep step = stepsByIdempotencyKey.get(idempotencyKey);
        if (step == null) {
            throw new IllegalArgumentException("unknown saga step idempotency key: " + idempotencyKey);
        }
        return step;
    }

    public void advanceTo(BookingSagaStatus nextStatus) {
        requireNotTerminal();
        if (nextStatus == BookingSagaStatus.COMPLETED || nextStatus == BookingSagaStatus.FAILED) {
            throw new IllegalArgumentException("use complete or fail for terminal saga states");
        }
        this.status = nextStatus;
        record(new BookingSagaAdvanced(nextEventId(), sagaId, now(), nextStatus));
    }

    public void recordStepSucceeded(String stepIdempotencyKey) {
        requireNotTerminal();
        BookingSagaStep step = step(stepIdempotencyKey);
        if (step.status() == BookingStepStatus.SUCCEEDED) {
            return;
        }
        step.succeed();
        record(new BookingSagaStepSucceeded(nextEventId(), sagaId, now(), step.name(), step.idempotencyKey()));
    }

    public void recordStepFailed(String stepIdempotencyKey, String reason) {
        requireNotTerminal();
        BookingSagaStep step = step(stepIdempotencyKey);
        if (step.status() == BookingStepStatus.FAILED && step.failureReason().orElse("").equals(reason)) {
            return;
        }
        step.fail(reason);
        record(new BookingSagaStepFailed(nextEventId(), sagaId, now(), step.name(), step.idempotencyKey(), reason));
    }

    public void retryStep(String stepIdempotencyKey) {
        requireNotTerminal();
        BookingSagaStep step = step(stepIdempotencyKey);
        step.scheduleRetry();
        record(new BookingSagaRetryScheduled(nextEventId(), sagaId, now(), step.name(), step.idempotencyKey(),
            step.attemptNumber()));
    }

    public void complete() {
        requireNotTerminal();
        this.status = BookingSagaStatus.COMPLETED;
        this.terminalReason = null;
        record(new BookingSagaCompleted(nextEventId(), sagaId, now()));
    }

    public void fail(String reason) {
        requireNotTerminal();
        this.status = BookingSagaStatus.FAILED;
        this.terminalReason = requireText(reason, "reason");
        record(new BookingSagaFailed(nextEventId(), sagaId, now(), terminalReason));
    }

    public void moveToManualReview(String reason) {
        requireNotTerminal();
        this.status = BookingSagaStatus.MANUAL_REVIEW;
        this.terminalReason = requireText(reason, "reason");
        record(new BookingSagaManualReviewRequired(nextEventId(), sagaId, now(), terminalReason));
    }

    public List<DomainEvent> pullEvents() {
        return eventRecorder.pullEvents();
    }

    public List<DomainEvent> peekEvents() {
        return eventRecorder.peekEvents();
    }

    public static BookingSagaStep stepPlan(String name, String idempotencyKey, Duration timeout, int retryLimit,
                                           String compensationAction) {
        return new BookingSagaStep(name, idempotencyKey, timeout, retryLimit, compensationAction);
    }

    private void addStep(BookingSagaStep step) {
        BookingSagaStep previous = stepsByIdempotencyKey.putIfAbsent(step.idempotencyKey(), step);
        if (previous != null) {
            throw new IllegalArgumentException("duplicate saga step idempotency key: " + step.idempotencyKey());
        }
    }

    private void requireNotTerminal() {
        if (status == BookingSagaStatus.COMPLETED || status == BookingSagaStatus.FAILED) {
            throw new IllegalStateException("saga is terminal: " + status);
        }
    }

    private void record(DomainEvent event) {
        eventRecorder.record(event);
    }

    private String nextEventId() {
        return eventRecorder.nextEventId();
    }

    private java.time.Instant now() {
        return eventRecorder.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
