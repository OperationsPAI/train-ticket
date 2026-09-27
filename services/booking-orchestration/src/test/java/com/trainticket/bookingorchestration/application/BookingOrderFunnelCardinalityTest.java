package com.trainticket.bookingorchestration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.bookingorchestration.observability.NoopBookingSagaMetrics;
import com.trainticket.bookingorchestration.observability.OrderFunnelMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Two reservations for two different orders must land on the same label set.
 *
 * <p>The funnel is handed the stage, the outcome and the channel and nothing else, so an order,
 * segment, traveler, offer or idempotency key that reached a label would split the series. This is
 * the cardinality guard the metric contract asks for, taken through the service rather than at the
 * recorder, where no identifier could be passed even by mistake.
 */
class BookingOrderFunnelCardinalityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);

    private record Recorded(String stage, String outcome, String channel) {}

    private static final class RecordingFunnel implements OrderFunnelMetrics {
        private final List<Recorded> transitions = new ArrayList<>();

        @Override
        public void transition(Stage stage, Outcome outcome, Channel channel) {
            transitions.add(new Recorded(stage.value(), outcome.value(), channel.value()));
        }
    }

    @Test
    void twoDifferentOrdersProduceTheSameBoundedLabelSet() {
        RecordingFunnel funnel = new RecordingFunnel();
        BookingOrchestrationService service = new BookingOrchestrationService(
            CLOCK,
            envelope -> { },
            new InMemoryBookingSagaRepository(),
            new InMemorySegmentBookingRepository(),
            new InMemoryPaymentIntentSagaReferenceRepository(),
            new InMemoryConsumedEventRepository(),
            new InMemoryBookingSagaStepDeadlineRepository(),
            new NoopBookingSagaMetrics(),
            funnel);

        List<Recorded> perOrder = new ArrayList<>();
        int index = 0;
        for (String suffix : List.of("aaaa", "bbbb")) {
            String orderId = "ord-" + suffix;
            String correlation = "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c6" + (index == 0 ? "a1" : "b1");
            index++;
            String sagaId = service
                .startSaga(new BookingOrchestrationService.StartSagaCommand(
                    orderId, "acc-" + suffix, "off-" + suffix,
                    List.of("tvl-" + suffix), List.of("seg-" + suffix)),
                    "idem-" + suffix, correlation)
                .sagaId();
            int before = funnel.transitions.size();
            service.requestReservation(
                sagaId,
                new BookingOrchestrationService.RequestReservationCommand("seg-" + suffix, "tvl-" + suffix, "sb-" + suffix),
                new ReservationFactsResolver.Facts(orderId, "seg-" + suffix, "tvl-" + suffix, "off-item-" + suffix,
                    "SECOND_CLASS", "ss-" + suffix, "2026-10-01", false),
                correlation);
            perOrder.addAll(funnel.transitions.subList(before, funnel.transitions.size()));
        }

        assertThat(perOrder).isNotEmpty();
        Set<Recorded> distinct = new HashSet<>(perOrder);
        assertThat(distinct)
            .as("both orders' reservations share one label set: %s", perOrder)
            .containsExactly(new Recorded("reservation", "attempted", "other"));
        // A leaked identifier would arrive as a fourth, differently-shaped label.
        assertThat(perOrder).allSatisfy(recorded -> assertThat(recorded).isEqualTo(new Recorded("reservation", "attempted", "other")));
    }
}
