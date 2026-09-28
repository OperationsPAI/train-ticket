package com.trainticket.bookingorchestration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.observability.NoopBookingSagaMetrics;
import com.trainticket.bookingorchestration.observability.NoopOrderFunnelMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingStepWaitSettingsTest {
    private static final String ORDER = "ord-01a0f000-0000-7000-8000-000000000001";
    private static final String TRAVELER = "tvl-01a0f000-0000-7000-8000-000000000002";
    private static final String SEGMENT = "seg-01a0f000-0000-7000-8000-000000000003";
    private static final String CORRELATION = "corr-01a0f000-0000-7000-8000-000000000004";

    @Test
    void thePlanCarriesTheWaitsTheSettingsState() {
        BookingSagaRepository sagas = new InMemoryBookingSagaRepository();
        BookingOrchestrationService service = service(sagas, new BookingStepWaitSettings(120, 45, 120, 900));

        String sagaId = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            ORDER, "acc-1", "ofi-1", List.of(TRAVELER), List.of(SEGMENT)), "idem-1", CORRELATION).sagaId();

        BookingSaga saga = sagas.findById(sagaId).orElseThrow();
        assertEquals(Duration.ofSeconds(120), saga.step(sagaId + ":risk").timeout());
        assertEquals(Duration.ofSeconds(45), saga.step(sagaId + ":" + SEGMENT).timeout());
        assertEquals(Duration.ofSeconds(120), saga.step(sagaId + ":invoice").timeout());
    }

    @Test
    void theDefaultsAreTheBudgetsTheConstantsCarried() {
        BookingStepWaitSettings defaults = BookingStepWaitSettings.defaults();

        assertEquals(Duration.ofMinutes(2), defaults.riskCheck());
        assertEquals(Duration.ofMinutes(5), defaults.reservation());
        assertEquals(Duration.ofMinutes(2), defaults.invoice());
        assertEquals(Duration.ofMinutes(15), defaults.changeReservations());
    }

    @Test
    void aWaitBelowOneSecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BookingStepWaitSettings(120, 0, 120, 900));
    }

    private static BookingOrchestrationService service(BookingSagaRepository sagas, BookingStepWaitSettings waits) {
        return new BookingOrchestrationService(Clock.fixed(Instant.parse("2026-09-28T05:00:00Z"), ZoneOffset.UTC),
            envelope -> { }, sagas, new InMemorySegmentBookingRepository(),
            new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository(),
            new InMemoryBookingSagaStepDeadlineRepository(), new NoopBookingSagaMetrics(),
            new NoopOrderFunnelMetrics(), waits);
    }
}
