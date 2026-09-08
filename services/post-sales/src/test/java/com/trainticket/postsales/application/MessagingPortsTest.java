package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessagingPortsTest {
    @Test
    void publisherWrapsDomainEventInContractEnvelope() throws Exception {
        PostSalesCase postSalesCase = PostSalesCase.open(
            "ord-test-2",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("oi-test-2", "seg-test-2", "tvl-test-2", "ent-test-2"),
            "CUSTOMER_REQUEST",
            "acct-test-2",
            "cmd-open-2",
            Instant.parse("2026-07-05T10:30:00Z"),
            "open-2",
            "corr-2"
        );

        EventEnvelope envelope = PostSalesMapper.toEnvelope(postSalesCase.domainEvents().get(1));

        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("PostSalesRequested", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("post-sales", envelope.producer());
        assertTrue(envelope.causationId().startsWith("cmd-"));
        assertTrue(envelope.correlationId().startsWith("corr-"));
        assertEquals(Instant.parse("2026-07-05T10:30:00Z"), envelope.occurredAt());
        assertTrue(envelope.payload().toString().contains("ord-test-2"));

        Map<?, ?> serialized = JsonMapper.builderWithJackson2Defaults().build().readValue(
            JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(envelope),
            Map.class
        );
        assertEquals(
            List.of("eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"),
            new ArrayList<>(serialized.keySet())
        );
        assertFalse(serialized.containsKey("sourceCommandId"));
        assertFalse(serialized.containsKey("attributes"));
    }

    @Test
    void postSalesRequestedPayloadUsesContractFieldNames() {
        PostSalesCase postSalesCase = PostSalesCase.open(
            "ord-test-5",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("oi-test-5", "seg-test-5", "tvl-test-5", "ent-test-5"),
            "CUSTOMER_REQUEST",
            "acct-test-5",
            "cmd-open-5",
            Instant.parse("2026-07-05T10:30:00Z"),
            "open-5",
            "corr-5"
        );

        Map<?, ?> payload = assertPayload(PostSalesMapper.toEnvelope(postSalesCase.domainEvents().get(1)).payload());

        assertEquals(List.of("caseId", "orderId", "requestType", "requestedAt"), new ArrayList<>(payload.keySet()));
        assertEquals("ord-test-5", payload.get("orderId"));
        assertEquals("REFUND_BY_RULE", payload.get("requestType"));
        assertEquals("2026-07-05T10:30:00Z", payload.get("requestedAt"));
    }

    @Test
    void postSalesApprovedPayloadUsesContractFieldNames() {
        PostSalesCase postSalesCase = approvedCase();
        EventEnvelope envelope = postSalesCase.domainEvents().stream()
            .filter(com.trainticket.postsales.domain.PostSalesApproved.class::isInstance)
            .map(PostSalesMapper::toEnvelope)
            .findFirst()
            .orElseThrow();

        Map<?, ?> payload = assertPayload(envelope.payload());

        assertEquals(List.of("caseId", "orderId", "approvedActions"), new ArrayList<>(payload.keySet()));
        assertEquals("ord-test-6", payload.get("orderId"));
        assertInstanceOf(Map.class, payload.get("approvedActions"));
    }

    @Test
    void postSalesAppliedPayloadUsesContractFieldNames() {
        PostSalesCase postSalesCase = approvedCase();
        postSalesCase.requestExecution(Instant.parse("2026-07-05T10:33:00Z"), "execute-6", "approve-6", "corr-6");
        postSalesCase.applyResult("applied by contract test", Instant.parse("2026-07-05T10:34:00Z"), "apply-6", "execute-6", "corr-6");
        EventEnvelope envelope = postSalesCase.domainEvents().stream()
            .filter(com.trainticket.postsales.domain.PostSalesApplied.class::isInstance)
            .map(PostSalesMapper::toEnvelope)
            .findFirst()
            .orElseThrow();

        Map<?, ?> payload = assertPayload(envelope.payload());

        assertEquals(List.of("caseId", "orderId", "resultSummary"), new ArrayList<>(payload.keySet()));
        assertEquals("ord-test-6", payload.get("orderId"));
        assertInstanceOf(Map.class, payload.get("resultSummary"));
    }

    @Test
    void subscriberHandlerDeduplicatesDuplicateEventId() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        PostSalesEventHandler handler = new PostSalesEventHandler(log, new NoOpPostSalesApplicationService());
        // JourneyOrderCreated, not JourneyOrderCancelled: the handler now rejects
        // uninteresting types BEFORE writing to the dedup log, so an event type it
        // does not act on records nothing and this test would assert 0 == 1 while
        // testing nothing about deduplication. Cancelled was never handled here --
        // the original assertion only passed because the write came first.
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d001",
            "JourneyOrderCreated",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d002",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d003",
            "journey-order",
            1,
            Map.of("journeyOrderId", "ord-test-3")
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, log.recorded.size());
    }

    @Test
    void handlesAncillaryCancellationWithEventIdDedupAndOpensRefundCase() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        InMemoryPostSalesPolicyContextStore contextStore = new InMemoryPostSalesPolicyContextStore();
        InMemoryPostSalesExternalEventProjectionStore projectionStore = new InMemoryPostSalesExternalEventProjectionStore();
        PostSalesApplicationService service = new PostSalesApplicationService(
            repository,
            ignored -> { },
            ignored -> java.util.Optional.empty(),
            contextStore,
            projectionStore,
            java.time.Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), java.time.ZoneOffset.UTC)
        );
        PostSalesEventHandler handler = new PostSalesEventHandler(
            log,
            service,
            new PostSalesExternalEventPolicy(service),
            (org.springframework.transaction.PlatformTransactionManager) null
        );
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d101",
            "AncillaryOrderItemCancelled",
            Instant.parse("2026-07-05T10:29:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d111",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d112",
            "ancillary-service",
            1,
            ancillaryCancelledPayload()
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        assertEquals(1, log.recorded.size());
        AncillaryPostSalesProjection projection = projectionStore.findAncillaryByItemId("aoi-1").orElseThrow();
        assertEquals("CANCELLED", projection.status());
        PostSalesCase opened = repository.findByIdempotencyKey("ancillary-service:evt-0194f2e0-7b3e-7610-8284-5c26e8b0d101:refund").orElseThrow();
        assertEquals(opened.caseId(), projection.postSalesCaseId());
        assertEquals(PostSalesCaseType.REFUND, opened.caseType());
        assertEquals(List.of("aoi-1"), opened.scope().orderItemRefs());
    }

    @Test
    void linksLaterAncillaryCancellationToExistingActiveRefundCase() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        InMemoryPostSalesExternalEventProjectionStore projectionStore = new InMemoryPostSalesExternalEventProjectionStore();
        PostSalesApplicationService service = new PostSalesApplicationService(
            repository,
            ignored -> { },
            ignored -> java.util.Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(),
            projectionStore,
            java.time.Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), java.time.ZoneOffset.UTC)
        );
        PostSalesCase activeMainRefund = PostSalesCase.rehydrate(
            "case-main-refund-1",
            "ord-ancillary-1",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("ticket-1", "seg-1", "tvl-1", "ent-1"),
            "CUSTOMER_REQUEST",
            "acct-1",
            "main-refund-key-1",
            PostSalesCaseStatus.OPENED,
            null,
            List.of(),
            null,
            null,
            List.of()
        );
        repository.save(activeMainRefund);
        PostSalesEventHandler handler = new PostSalesEventHandler(
            log,
            service,
            new PostSalesExternalEventPolicy(service),
            (org.springframework.transaction.PlatformTransactionManager) null
        );
        Map<String, Object> secondCancellationPayload = ancillaryCancelledPayload();
        secondCancellationPayload.put("ancillaryOrderItemId", "aoi-2");
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d104",
            "AncillaryOrderItemCancelled",
            Instant.parse("2026-07-05T10:29:30Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d117",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d118",
            "ancillary-service",
            1,
            secondCancellationPayload
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        assertEquals(1, log.recorded.size());
        assertEquals(1, repository.findAll().size());
        AncillaryPostSalesProjection projection = projectionStore.findAncillaryByItemId("aoi-2").orElseThrow();
        assertEquals("CANCELLED", projection.status());
        assertEquals("case-main-refund-1", projection.postSalesCaseId());
    }

    @Test
    void handlesDispatchRideEndedProjection() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        InMemoryPostSalesExternalEventProjectionStore projectionStore = new InMemoryPostSalesExternalEventProjectionStore();
        PostSalesApplicationService service = new PostSalesApplicationService(
            new InMemoryPostSalesRepository(),
            ignored -> { },
            ignored -> java.util.Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(),
            projectionStore,
            java.time.Clock.systemUTC()
        );
        PostSalesEventHandler handler = new PostSalesEventHandler(
            log,
            service,
            new PostSalesExternalEventPolicy(service),
            (org.springframework.transaction.PlatformTransactionManager) null
        );
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d102",
            "RideEnded",
            Instant.parse("2026-07-05T10:40:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d113",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0d114",
            "dispatch",
            1,
            dispatchRideEndedPayload()
        );

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope));

        DispatchPostSalesProjection projection = projectionStore.findDispatchByRideRequestId("rrq-1").orElseThrow();
        assertEquals("COMPLETED", projection.status());
        assertEquals("fare-final-1", projection.finalFareRef());
    }

    @Test
    void unknownEventTypeIsAckSkipped() {
        RecordingConsumedEventLog log = new RecordingConsumedEventLog();
        PostSalesEventHandler handler = new PostSalesEventHandler(log, new NoOpPostSalesApplicationService());
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d103",
            "ConformantUnknownFact",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d115",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0d116",
            "dispatch",
            1,
            Map.of("status", "NEW_STATE")
        );

        assertDoesNotThrow(() -> assertEquals(EventSubscriber.HandlerResult.SUCCESS, handler.handle(envelope)));
        // Nothing recorded: an unknown type is now rejected before the dedup write.
        // That write was charged to every event on every subscribed stream, and
        // post-sales subscribes to all of events:capacity-availability for one
        // event type it sees almost never -- 7325 of 12606 pending messages on the
        // live cluster. Four consumer threads each doing a needless write starved
        // the HTTP thread until /readyz failed and the pod was restarted.
        //
        // Dedup is not weakened: an event that reaches no handler has no side
        // effect to deduplicate.
        assertEquals(0, log.recorded.size());
    }

    @Test
    void fakeSubscriberUsesDeliveryCountForDlqDecision() {
        FakeDeliveryCounterSubscriber subscriber = new FakeDeliveryCounterSubscriber();
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d004",
            "JourneyOrderCancelled",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d005",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d003",
            "journey-order",
            1,
            Map.of("journeyOrderId", "ord-test-4")
        );

        for (int attempt = 1; attempt < 5; attempt++) {
            subscriber.deliver(envelope, attempt, ignored -> EventSubscriber.HandlerResult.TRANSIENT_FAILURE);
        }
        subscriber.deliver(envelope, 5, ignored -> EventSubscriber.HandlerResult.TRANSIENT_FAILURE);

        assertEquals(1, subscriber.dlq.size());
        assertEquals("evt-0194f2e0-7b3e-7610-8284-5c26e8b0d004", subscriber.dlq.getFirst().eventId());
    }

    private static Map<String, Object> dispatchRideEndedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rideRequestId", "rrq-1");
        payload.put("rideAssignmentId", "ras-1");
        payload.put("riderAccountId", "acct-1");
        payload.put("travelerRef", "tvl-1");
        payload.put("pickupRef", "place-pickup");
        payload.put("dropoffRef", "place-dropoff");
        payload.put("driverRef", "drv-1");
        payload.put("vehicleRef", "veh-1");
        payload.put("startedAt", "2026-07-05T10:10:00Z");
        payload.put("endedAt", "2026-07-05T10:40:00Z");
        payload.put("finalFareRef", "fare-final-1");
        payload.put("status", "COMPLETED");
        return payload;
    }

    private static Map<String, Object> ancillaryCancelledPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ancillaryOrderItemId", "aoi-1");
        payload.put("journeyOrderId", "ord-ancillary-1");
        payload.put("travelerRef", "tvl-1");
        payload.put("segmentRef", "seg-1");
        payload.put("catalogItemId", "aci-1");
        payload.put("serviceType", "MEAL");
        payload.put("payableAmount", Map.of("currency", "CNY", "minorUnits", 1000));
        payload.put("refundableAmount", Map.of("currency", "CNY", "minorUnits", 800));
        payload.put("reasonCode", "JOURNEY_ORDER_CANCELLED");
        payload.put("source", "JOURNEY_ORDER_CANCELLED");
        payload.put("previousStatus", "CONFIRMED");
        payload.put("status", "CANCELLED");
        payload.put("cancelledAt", "2026-07-05T10:29:00Z");
        payload.put("aggregateVersion", 4);
        return payload;
    }

    private static PostSalesCase approvedCase() {
        PostSalesCase postSalesCase = PostSalesCase.open(
            "ord-test-6",
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket("oi-test-6", "seg-test-6", "tvl-test-6", "ent-test-6"),
            "CUSTOMER_REQUEST",
            "acct-test-6",
            "cmd-open-6",
            Instant.parse("2026-07-05T10:30:00Z"),
            "open-6",
            "corr-6"
        );
        postSalesCase.beginEvaluation(Instant.parse("2026-07-05T10:31:00Z"), "evaluate-6", "open-6", "corr-6");
        postSalesCase.recordDecision(
            com.trainticket.postsales.domain.PostSalesDecision.refund(
                postSalesCase.caseId(),
                true,
                "REFUNDABLE",
                new com.trainticket.postsales.domain.RuleEvaluationSnapshot(
                    "fare-eval-6",
                    "rule-snapshot-6",
                    "rule-v1",
                    Instant.parse("2026-07-05T10:31:00Z"),
                    Map.of("orderId", "ord-test-6")
                ),
                com.trainticket.postsales.domain.AmountDecisionSnapshot.refund(
                    com.trainticket.postsales.domain.Money.of("0.00", "USD"),
                    com.trainticket.postsales.domain.Money.of("5.00", "USD"),
                    "contract test refund"
                ),
                Instant.parse("2026-07-05T10:31:00Z"),
                Instant.parse("2026-07-05T11:31:00Z")
            ),
            false,
            false,
            Instant.parse("2026-07-05T10:31:00Z"),
            "quote-6",
            "evaluate-6",
            "corr-6"
        );
        postSalesCase.approve("approval-6", Instant.parse("2026-07-05T10:32:00Z"), "approve-6", "quote-6", "corr-6");
        return postSalesCase;
    }

    private static Map<?, ?> assertPayload(Object payload) {
        assertInstanceOf(Map.class, payload);
        return (Map<?, ?>) payload;
    }

    private static final class FakeDeliveryCounterSubscriber {
        private final List<EventEnvelope> dlq = new ArrayList<>();

        void deliver(EventEnvelope envelope, int deliveryCount, EventSubscriber.EventHandler handler) {
            EventSubscriber.HandlerResult result = deliveryCount >= 5
                ? EventSubscriber.HandlerResult.FATAL_FAILURE
                : handler.handle(envelope);
            if (result == EventSubscriber.HandlerResult.FATAL_FAILURE) {
                dlq.add(envelope);
            }
        }
    }

    private static final class NoOpPostSalesApplicationService extends PostSalesApplicationService {
        NoOpPostSalesApplicationService() {
            super(new InMemoryPostSalesRepository(), ignored -> { }, ignored -> java.util.Optional.empty(), new InMemoryPostSalesPolicyContextStore(), java.time.Clock.systemUTC());
        }
    }

    private static final class RecordingConsumedEventLog implements ConsumedEventLog {
        private final List<String> recorded = new ArrayList<>();

        @Override
        public boolean recordIfFirstSeen(String eventId) {
            if (recorded.contains(eventId)) {
                return false;
            }
            recorded.add(eventId);
            return true;
        }
    }
}
