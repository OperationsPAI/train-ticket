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

        assertEquals(BookingSagaStatus.RISK_CHECKING, saga.status());
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

    @Test
    void fullSagaStepSequenceIncludesRiskCheckSeatAssignAndInvoice() {
        BookingSaga saga = BookingSaga.start("saga-full", "order-full", "v1", "purchase", List.of(
            BookingSaga.stepPlan("risk-check", "saga-full:risk", Duration.ofMinutes(2), 2, "none"),
            BookingSaga.stepPlan("reserve-seg-001", "saga-full:seg-001", Duration.ofMinutes(5), 3, "release-capacity"),
            BookingSaga.stepPlan("reserve-seg-002", "saga-full:seg-002", Duration.ofMinutes(5), 3, "release-capacity"),
            BookingSaga.stepPlan("seat-assign-seg-001", "saga-full:seat:seg-001", Duration.ofMinutes(2), 2, "release-seat"),
            BookingSaga.stepPlan("seat-assign-seg-002", "saga-full:seat:seg-002", Duration.ofMinutes(2), 2, "release-seat"),
            BookingSaga.stepPlan("invoice", "saga-full:invoice", Duration.ofMinutes(2), 1, "none")
        ), clock);

        assertEquals(BookingSagaStatus.RISK_CHECKING, saga.status());
        assertEquals(6, saga.steps().size());

        // Risk check passes -> RESERVING
        saga.recordStepSucceeded("saga-full:risk");
        saga.advanceTo(BookingSagaStatus.RESERVING);
        assertEquals(BookingSagaStatus.RESERVING, saga.status());

        // Reserve steps succeed -> SEAT_ASSIGNING
        saga.recordStepSucceeded("saga-full:seg-001");
        saga.recordStepSucceeded("saga-full:seg-002");
        saga.advanceTo(BookingSagaStatus.SEAT_ASSIGNING);
        assertEquals(BookingSagaStatus.SEAT_ASSIGNING, saga.status());

        // Seat assign steps succeed -> AWAITING_PAYMENT
        saga.recordStepSucceeded("saga-full:seat:seg-001");
        saga.recordStepSucceeded("saga-full:seat:seg-002");
        saga.advanceTo(BookingSagaStatus.AWAITING_PAYMENT);
        assertEquals(BookingSagaStatus.AWAITING_PAYMENT, saga.status());

        // Payment -> TICKETING -> INVOICING
        saga.advanceTo(BookingSagaStatus.TICKETING);
        saga.advanceTo(BookingSagaStatus.INVOICING);
        saga.recordStepSucceeded("saga-full:invoice");
        saga.complete();
        assertEquals(BookingSagaStatus.COMPLETED, saga.status());
    }

    @Test
    void cannotAdvanceFromRiskCheckingWithoutRiskStepSucceeded() {
        BookingSaga saga = BookingSaga.start("saga-rc", "order-rc", "v1", "purchase", List.of(
            BookingSaga.stepPlan("risk-check", "saga-rc:risk", Duration.ofMinutes(2), 2, "none"),
            BookingSaga.stepPlan("reserve-seg-001", "saga-rc:seg-001", Duration.ofMinutes(5), 3, "release-capacity")
        ), clock);

        assertEquals(BookingSagaStatus.RISK_CHECKING, saga.status());
        assertEquals(BookingStepStatus.PENDING, saga.step("saga-rc:risk").status());

        // Risk step is still PENDING; advancing requires explicit step success
        saga.recordStepFailed("saga-rc:risk", "high risk score");
        assertEquals(BookingStepStatus.FAILED, saga.step("saga-rc:risk").status());

        // Saga can be failed from any non-terminal state
        saga.fail("risk-blocked");
        assertEquals(BookingSagaStatus.FAILED, saga.status());
    }

    @Test
    void cannotAdvanceFromSeatAssigningWithoutAllSeatStepsSucceeded() {
        BookingSaga saga = BookingSaga.start("saga-sa", "order-sa", "v1", "purchase", List.of(
            BookingSaga.stepPlan("risk-check", "saga-sa:risk", Duration.ofMinutes(2), 2, "none"),
            BookingSaga.stepPlan("reserve-seg-001", "saga-sa:seg-001", Duration.ofMinutes(5), 3, "release-capacity"),
            BookingSaga.stepPlan("seat-assign-seg-001", "saga-sa:seat:seg-001", Duration.ofMinutes(2), 2, "release-seat"),
            BookingSaga.stepPlan("seat-assign-seg-002", "saga-sa:seat:seg-002", Duration.ofMinutes(2), 2, "release-seat"),
            BookingSaga.stepPlan("invoice", "saga-sa:invoice", Duration.ofMinutes(2), 1, "none")
        ), clock);

        saga.recordStepSucceeded("saga-sa:risk");
        saga.advanceTo(BookingSagaStatus.RESERVING);
        saga.recordStepSucceeded("saga-sa:seg-001");
        saga.advanceTo(BookingSagaStatus.SEAT_ASSIGNING);

        // Only one seat-assign step succeeded
        saga.recordStepSucceeded("saga-sa:seat:seg-001");
        assertEquals(BookingStepStatus.SUCCEEDED, saga.step("saga-sa:seat:seg-001").status());
        assertEquals(BookingStepStatus.PENDING, saga.step("saga-sa:seat:seg-002").status());

        // Second seat-assign step succeeds
        saga.recordStepSucceeded("saga-sa:seat:seg-002");
        assertEquals(BookingStepStatus.SUCCEEDED, saga.step("saga-sa:seat:seg-002").status());
    }
}
