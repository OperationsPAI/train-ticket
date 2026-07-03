package com.trainticket.bookingorchestration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingSagaTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void startsAndAdvancesSagaWithCoordinationFacts() {
        BookingSaga saga = BookingSaga.start("saga-1", "order-1", "v1", "initial", List.of(
            BookingSaga.stepPlan("HoldCapacity", "booking-1:hold", Duration.ofMinutes(5), 2, "ReleaseHold"),
            BookingSaga.stepPlan("ReserveProvider", "booking-1:reserve", Duration.ofMinutes(2), 1,
                "CancelProviderReservation")
        ), clock);

        assertEquals(BookingSagaStatus.RESERVING, saga.status());
        assertEquals("order-1:v1:initial", saga.idempotencyKey());
        assertInstanceOf(BookingEvent.BookingSagaStarted.class, saga.peekEvents().getFirst());

        saga.recordStepSucceeded("booking-1:hold");
        saga.advanceTo(BookingSagaStatus.AWAITING_PAYMENT);

        assertEquals(BookingStepStatus.SUCCEEDED, saga.step("booking-1:hold").status());
        assertEquals(BookingSagaStatus.AWAITING_PAYMENT, saga.status());
    }

    @Test
    void tracksStepsIdempotentlyAndRetriesWithinLimit() {
        BookingSaga saga = BookingSaga.start("saga-1", "order-1", "v1", "initial", List.of(
            BookingSaga.stepPlan("ReserveProvider", "booking-1:reserve", Duration.ofMinutes(2), 1,
                "CancelProviderReservation")
        ), clock);
        saga.pullEvents();

        saga.recordStepSucceeded("booking-1:reserve");
        saga.recordStepSucceeded("booking-1:reserve");
        assertEquals(1, saga.peekEvents().stream()
            .filter(BookingEvent.BookingSagaStepSucceeded.class::isInstance)
            .count());

        BookingSaga retryingSaga = BookingSaga.start("saga-2", "order-2", "v1", "initial", List.of(
            BookingSaga.stepPlan("ReserveProvider", "booking-2:reserve", Duration.ofMinutes(2), 1,
                "CancelProviderReservation")
        ), clock);
        retryingSaga.pullEvents();

        retryingSaga.recordStepFailed("booking-2:reserve", "provider timeout");
        retryingSaga.retryStep("booking-2:reserve");

        assertEquals(BookingStepStatus.PENDING, retryingSaga.step("booking-2:reserve").status());
        assertEquals(1, retryingSaga.step("booking-2:reserve").attemptNumber());
        assertInstanceOf(BookingEvent.BookingSagaRetryScheduled.class, retryingSaga.peekEvents().getLast());

        retryingSaga.recordStepFailed("booking-2:reserve", "provider timeout again");
        assertThrows(IllegalStateException.class, () -> retryingSaga.retryStep("booking-2:reserve"));
    }

    @Test
    void recordsTerminalFailureAndRejectsFurtherProgress() {
        BookingSaga saga = BookingSaga.start("saga-1", "order-1", "v1", "initial", List.of(
            BookingSaga.stepPlan("HoldCapacity", "booking-1:hold", Duration.ofMinutes(5), 0, "ReleaseHold")
        ), clock);
        saga.pullEvents();

        saga.fail("capacity and provider unavailable");

        assertEquals(BookingSagaStatus.FAILED, saga.status());
        assertEquals("capacity and provider unavailable", saga.terminalReason().orElseThrow());
        assertInstanceOf(BookingEvent.BookingSagaFailed.class, saga.peekEvents().getFirst());
        assertThrows(IllegalStateException.class, () -> saga.advanceTo(BookingSagaStatus.CONFIRMING));
    }
}
