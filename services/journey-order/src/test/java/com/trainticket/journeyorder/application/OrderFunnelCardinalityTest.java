package com.trainticket.journeyorder.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.out.IdentityVerificationPort;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.observability.OrderFunnelMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Two orders placed by different accounts, for different offers, under different idempotency keys
 * must land on the same label set. If any business identifier reached a label the counts would
 * split, so this is the cardinality guard the contract asks for.
 */
class OrderFunnelCardinalityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void twoDifferentOrdersProduceTheSameBoundedLabelSet() {
        RecordingFunnel recorder = new RecordingFunnel();
        OrderManagementService service = new OrderManagementService(
            event -> { }, CLOCK, new InMemoryJourneyOrderStateRepository(),
            (request, orderIntentId, idempotencyKey, correlationId) ->
                new IdentityVerificationPort.PreOrderCheckResult("PASS", "poc-test"),
            request -> { throw new IllegalStateException("offer resolution is not used by this test"); },
            Optional.empty(), recorder);

        JourneyOrderRequest first = new JourneyOrderRequest("acc-1", "off-1", 1, List.of("tvl-1"), List.of("seg-1"));
        JourneyOrderRequest second = new JourneyOrderRequest("acc-2", "off-2", 3, List.of("tvl-2"), List.of("seg-2"));
        service.createOrderFromOffer(first, "idem-first", "corr-first", OrderCreationFixture.snapshot(first, 10_000));
        service.createOrderFromOffer(second, "idem-second", "corr-second", OrderCreationFixture.snapshot(second, 20_000));

        assertThat(recorder.labels).containsExactly(
            List.of("order", "attempted", "web"),
            List.of("order", "accepted", "web"),
            List.of("order", "attempted", "web"),
            List.of("order", "accepted", "web"));
        // No order id, account id, offer id or idempotency key survives into a label.
        assertThat(recorder.labels).allSatisfy(labels -> assertThat(labels).hasSize(3));
    }

    @Test
    void aTerminalRejectionIsCountedOnceWithTheSameLabels() {
        RecordingFunnel recorder = new RecordingFunnel();
        OrderManagementService service = new OrderManagementService(
            event -> { }, CLOCK, new InMemoryJourneyOrderStateRepository(),
            (request, orderIntentId, idempotencyKey, correlationId) ->
                new IdentityVerificationPort.PreOrderCheckResult("REJECT", "poc-test"),
            request -> { throw new IllegalStateException("offer resolution is not used by this test"); },
            Optional.empty(), recorder);
        JourneyOrderRequest request = new JourneyOrderRequest("acc-9", "off-9", 1, List.of("tvl-9"), List.of("seg-9"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> service.createOrderFromOffer(request, "idem-9", "corr-9", OrderCreationFixture.snapshot(request, 5_000)));

        assertThat(recorder.labels).containsExactly(
            List.of("order", "attempted", "web"),
            List.of("order", "rejected", "web"));
    }

    private static final class RecordingFunnel implements OrderFunnelMetrics {
        private final List<List<String>> labels = new ArrayList<>();

        @Override
        public void transition(Stage stage, Outcome outcome, Channel channel) {
            labels.add(List.of(stage.value(), outcome.value(), channel.value()));
        }
    }
}
