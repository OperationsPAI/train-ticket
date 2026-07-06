package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingTest {
    @Test
    void publisherWrapsDomainEventInCanonicalEnvelope() {
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "ord-1", "item-1", "fare", Money.of("CNY", "10.00"), "policy-v1", "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e205",
            Instant.parse("2026-07-05T10:00:00Z"), Instant.parse("2026-07-05T10:00:01Z"), "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0e201", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e101");

        service.saveAndPublish(recognition);

        EventEnvelope envelope = publisher.published.getFirst();
        assertTrue(envelope.eventId().startsWith("evt-"));
        assertEquals("RevenueRecognized", envelope.eventType());
        assertEquals("finance-settlement", envelope.producer());
        assertEquals("corr-0194f2e0-7b3e-7610-8284-5c26e8b0e101", envelope.correlationId());
        assertEquals("cmd-0194f2e0-7b3e-7610-8284-5c26e8b0e201", envelope.causationId());
        assertEquals(1, envelope.schemaVersion());
        assertEquals(8, EventEnvelope.class.getRecordComponents().length);
        assertEquals(1000L, ((Map<?, ?>) ((Map<?, ?>) envelope.payload()).get("amount")).get("minorUnits"));
    }

    @Test
    void eventEnvelopeAcceptsMissingOptionalCausationId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = """
            {
              \"eventId\": \"evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222\",
              \"eventType\": \"PaymentCaptured\",
              \"schemaVersion\": 1,
              \"producer\": \"payment\",
              \"correlationId\": \"corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444\",
              \"occurredAt\": \"2026-07-03T10:30:00.000Z\",
              \"payload\": {
                \"paymentIntentId\": \"pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789\",
                \"capturedAmount\": {\"currency\": \"CNY\", \"minorUnits\": 35000},
                \"channel\": \"wechat_pay\",
                \"channelTransactionId\": \"wx_txn_20260703_a1b2c3\"
              }
            }
            """;

        EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);

        assertEquals("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222", envelope.eventId());
        assertNull(envelope.causationId());
    }

    @Test
    void subscriberHandlerDeduplicatesDuplicateEventId() {
        InMemoryConsumedEventLogRepository repository = new InMemoryConsumedEventLogRepository();
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            repository,
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC)
        );
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e003", "PaymentCaptured", Instant.parse("2026-07-05T09:59:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e101", "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e205",
            "payment", 1, Map.of("paymentIntentId", "pi-1"));

        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope));
        assertEquals(1, repository.saveCount);
    }

    @Test
    void handlerConsumesPaymentIntentCreatedThenPaymentCapturedUsingContractPayloadFields() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        PaymentIntentOrderReferenceRepository orderReferences = new InMemoryPaymentIntentOrderReferenceRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryRevenueRepository revenueRepository = new InMemoryRevenueRepository();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            revenueRepository, new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents,
            orderReferences,
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            service
        );

        HandlerResult createdResult = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e006", "PaymentIntentCreated", Instant.parse("2026-07-03T10:29:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null,
            "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "businessRef", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "purpose", "purchase",
                "amount", Map.of("currency", "CNY", "minorUnits", 35000),
                "payerRef", "acct-0194f2e0-7b3e-7610-0284-5c26e8b0c654",
                "idempotencyKey", "idem-0194f2e0-7b3e-7610-0284-5c26e8b0c777",
                "createdAt", "2026-07-03T10:29:00Z"
            )));
        HandlerResult capturedResult = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e007", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null,
            "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"
            )));

        assertEquals(HandlerResult.SUCCESS, createdResult);
        assertEquals(HandlerResult.SUCCESS, capturedResult);
        EventEnvelope published = publisher.published.getFirst();
        assertEquals("RevenueRecognized", published.eventType());
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321", ((Map<?, ?>) published.payload()).get("orderId"));
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321", ((Map<?, ?>) published.payload()).get("orderItemId"));
        assertEquals(35000L, ((Map<?, ?>) ((Map<?, ?>) published.payload()).get("amount")).get("minorUnits"));
    }

    @Test
    void paymentCapturedWithUnknownIntentIsTransientAndNotConsumed() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), new RecordingPublisher(), new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents,
            new InMemoryPaymentIntentOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC),
            service
        );

        EventEnvelope captured = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e008", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"), "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0e206",
            "payment", 1, Map.of(
                "paymentIntentId", "pi-unknown",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3"
            ));

        assertEquals(HandlerResult.TRANSIENT_FAILURE, handler.handle(captured));
        assertEquals(0, consumedEvents.saveCount);
    }

    @Test
    void postSalesAppliedUsesApprovedRefundAmountForRevenueReductionAndReconciliation() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents, new InMemoryPaymentIntentOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), service);

        handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e107", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "businessRef", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3")));
        handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e108", "PostSalesApproved", Instant.parse("2026-07-03T10:31:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "post-sales", 1, Map.of(
                "caseId", "psc-1",
                "orderId", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "approvedActions", Map.of(
                    "decisionKind", "REFUND",
                    "approvalRef", "approval-1",
                    "refund", Map.of("orderId", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321", "amount", Map.of("currency", "CNY", "minorUnits", 8750)),
                    "steps", List.of()))));
        HandlerResult applied = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e109", "PostSalesApplied", Instant.parse("2026-07-03T10:32:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "post-sales", 1, Map.of(
                "caseId", "psc-1",
                "orderId", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "resultSummary", Map.of("description", "applied"))));

        assertEquals(HandlerResult.SUCCESS, applied);
        EventEnvelope reduction = publisher.published.stream().filter(e -> "RevenueRecognitionReversed".equals(e.eventType())).findFirst().orElseThrow();
        assertEquals("fare", ((Map<?, ?>) reduction.payload()).get("componentCode"));
        assertEquals(8750L, ((Map<?, ?>) ((Map<?, ?>) reduction.payload()).get("amount")).get("minorUnits"));
        assertEquals("post-sales refund applied", ((Map<?, ?>) reduction.payload()).get("reversalReason"));
        assertTrue(publisher.published.stream().anyMatch(e -> "ReconciliationCompleted".equals(e.eventType())));
    }

    @Test
    void postSalesAppliedWithApprovedRefundButNoRecognizedRevenueOpensCaseAndConsumesEvent() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents, new InMemoryPaymentIntentOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), service);

        handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e111", "PostSalesApproved", Instant.parse("2026-07-03T10:31:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "post-sales", 1, Map.of(
                "caseId", "psc-lag",
                "orderId", "ord-refund-lag",
                "approvedActions", Map.of(
                    "decisionKind", "REFUND",
                    "approvalRef", "approval-lag",
                    "refund", Map.of("orderId", "ord-refund-lag", "amount", Map.of("currency", "CNY", "minorUnits", 8750)),
                    "steps", List.of()))));

        HandlerResult applied = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e112", "PostSalesApplied", Instant.parse("2026-07-03T10:32:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "post-sales", 1, Map.of(
                "caseId", "psc-lag",
                "orderId", "ord-refund-lag",
                "resultSummary", Map.of("description", "applied"))));

        assertEquals(HandlerResult.SUCCESS, applied);
        assertEquals(2, consumedEvents.saveCount);
        EventEnvelope opened = publisher.published.stream().filter(e -> "ReconciliationCaseOpened".equals(e.eventType())).findFirst().orElseThrow();
        assertEquals("refund-lag", ((Map<?, ?>) opened.payload()).get("differenceType"));
        assertEquals("ord-refund-lag", ((Map<?, ?>) opened.payload()).get("orderId"));
        assertEquals(-8750L, ((Map<?, ?>) ((Map<?, ?>) opened.payload()).get("expectedAmount")).get("minorUnits"));
        assertFalse(publisher.published.stream().anyMatch(e -> "RevenueRecognitionReversed".equals(e.eventType())));
    }

    @Test
    void operationalFactUsesSegmentReservationRequestedIndexForOrderReference() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents, new InMemoryPaymentIntentOrderReferenceRepository(), new InMemorySegmentBookingOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), service);

        handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e207", "SegmentReservationRequested", Instant.parse("2026-07-03T10:28:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "booking-orchestration", 1, Map.of(
                "segmentBookingId", "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
                "journeyOrderId", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "segmentRef", "seg-1",
                "travelerRef", "trav-1",
                "idempotencyKey", "idem-1")));
        handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e208", "PaymentCaptured", Instant.parse("2026-07-03T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "payment", 1, Map.of(
                "paymentIntentId", "pi-0194f2e0-7b3e-7610-0284-5c26e8b0c789",
                "businessRef", "ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321",
                "capturedAmount", Map.of("currency", "CNY", "minorUnits", 35000),
                "channel", "wechat_pay",
                "channelTransactionId", "wx_txn_20260703_a1b2c3")));
        HandlerResult confirmed = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e209", "ProviderReservationConfirmed", Instant.parse("2026-07-03T10:31:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "provider-integration", 1, Map.of(
                "segmentBookingId", "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
                "providerReference", "pr-1",
                "normalizedEvidence", "confirmed")));

        assertEquals(HandlerResult.SUCCESS, confirmed);
        EventEnvelope completed = publisher.published.stream().filter(e -> "ReconciliationCompleted".equals(e.eventType())).findFirst().orElseThrow();
        assertEquals("ord-0194f2e0-7b3e-7610-0284-5c26e8b0c321", ((Map<?, ?>) completed.payload()).get("orderId"));
        assertFalse(((String) ((Map<?, ?>) completed.payload()).get("orderId")).startsWith("order-unknown-for-"));
    }

    @Test
    void operationalFactWithoutSegmentIndexOpensUnassignedCaseWithoutSyntheticOrderKey() {
        InMemoryConsumedEventLogRepository consumedEvents = new InMemoryConsumedEventLogRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(), new InMemoryReconciliationRepository(), publisher, new DomainEventEnvelopeMapper());
        FinanceSettlementEventHandler handler = new FinanceSettlementEventHandler(
            consumedEvents, new InMemoryPaymentIntentOrderReferenceRepository(), new InMemorySegmentBookingOrderReferenceRepository(),
            Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC), service);

        HandlerResult result = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e210", "SegmentBookingCancelled", Instant.parse("2026-07-03T10:31:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e105", null, "booking-orchestration", 1, Map.of(
                "segmentBookingId", "sb-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
                "reason", "refund")));

        assertEquals(HandlerResult.SUCCESS, result);
        EventEnvelope opened = publisher.published.stream().filter(e -> "ReconciliationCaseOpened".equals(e.eventType())).findFirst().orElseThrow();
        assertEquals("", ((Map<?, ?>) opened.payload()).get("orderId"));
        assertTrue(((String) ((Map<?, ?>) opened.payload()).get("description")).contains("missing SegmentReservationRequested index"));
        assertFalse(((String) ((Map<?, ?>) opened.payload()).get("description")).contains("order-unknown-for-"));
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
