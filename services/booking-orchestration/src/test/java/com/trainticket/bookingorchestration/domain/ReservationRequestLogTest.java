package com.trainticket.bookingorchestration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationRequestLogTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void createsLogWithCorrectFields() {
        ReservationRequestLog log = new ReservationRequestLog(
            "segment-booking-1", "provider-1", "ReserveSegment",
            "idempotency-key-1", 1
        );

        assertEquals("segment-booking-1", log.segmentBookingId());
        assertEquals("provider-1", log.providerId());
        assertEquals("ReserveSegment", log.operation());
        assertEquals("idempotency-key-1", log.idempotencyKey());
        assertEquals(1, log.attemptNo());
        assertFalse(log.responseRecorded());
        assertFalse(log.timedOut());
    }

    @Test
    void recordsResponseOnce() {
        ReservationRequestLog log = new ReservationRequestLog(
            "segment-booking-1", "provider-1", "ReserveSegment",
            "idempotency-key-1", 1
        );

        log.recordResponse("confirmed");
        assertTrue(log.responseRecorded());
        assertEquals("confirmed", log.responseSummary());

        assertThrows(IllegalStateException.class, () -> log.recordResponse("duplicate"));
    }

    @Test
    void recordsTimeout() {
        ReservationRequestLog log = new ReservationRequestLog(
            "segment-booking-1", "provider-1", "ReserveSegment",
            "idempotency-key-1", 1
        );

        log.recordTimeout();
        assertTrue(log.timedOut());
    }

    @Test
    void refusesTimeoutAfterResponse() {
        ReservationRequestLog log = new ReservationRequestLog(
            "segment-booking-1", "provider-1", "ReserveSegment",
            "idempotency-key-1", 1
        );

        log.recordResponse("confirmed");
        assertThrows(IllegalStateException.class, log::recordTimeout);
    }

    @Test
    void rejectsInvalidAttemptNo() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationRequestLog(
            "segment-booking-1", "provider-1", "ReserveSegment",
            "key-1", 0
        ));
    }

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationRequestLog(
            "", "provider-1", "ReserveSegment", "key-1", 1
        ));
    }
}
