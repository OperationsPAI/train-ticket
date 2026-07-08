package com.trainticket.journeyorder.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.InMemoryEventBus;
import com.trainticket.platformkit.messaging.InMemoryEventPublisher;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.TransientDataAccessResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderManagementServiceTest {

    private InMemoryEventPublisher eventPublisher;
    private List<EventEnvelope> published;
    private OrderManagementService service;
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        eventPublisher = new InMemoryEventPublisher(eventBus);
        published = new ArrayList<>();
        service = new OrderManagementService(envelope -> {
            eventPublisher.publish(envelope);
            published.add(envelope);
        }, FIXED_CLOCK);
    }

    @Test
    void createOrderPublishesJourneyOrderCreated() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        JourneyOrderResult result = service.createOrder(request, "idem-1", "corr-1");

        assertEquals("account-1", result.accountId());
        assertEquals("offer-1", result.offerId());
        assertEquals("CREATED", result.status());
        assertTrue(result.orderId() != null && result.orderId().startsWith("ord-"));

        assertEquals(1, published().size());
        EventEnvelope envelope = published().getFirst();
        assertEquals("JourneyOrderCreated", envelope.eventType());
        assertEquals("journey-order", envelope.producer());
    }


    @Test
    void createdEventPayloadMatchesContractShape() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        service.createOrder(request, "idem-contract-created", "corr-1");

        Map<String, Object> payload = (Map<String, Object>) published().getFirst().payload();
        assertEquals(java.util.Set.of("orderId", "accountId", "offerId", "monetarySummary", "travelerRefs", "segmentRefs", "createdAt"), payload.keySet());
        assertTrue(String.valueOf(payload.get("orderId")).startsWith("ord-"));
        assertEquals("account-1", payload.get("accountId"));
        assertEquals("offer-1", payload.get("offerId"));
        assertEquals("2026-07-05T10:00:00Z", payload.get("createdAt"));
        assertEquals(List.of("seg-1"), payload.get("segmentRefs"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> travelerRefs = (List<Map<String, Object>>) payload.get("travelerRefs");
        assertEquals(1, travelerRefs.size());
        assertEquals(java.util.Set.of("travelerId", "travelerType"), travelerRefs.getFirst().keySet());
        assertEquals("tvl-1", travelerRefs.getFirst().get("travelerId"));
        assertEquals("ADULT", travelerRefs.getFirst().get("travelerType"));
    }

    @Test
    void idempotentCreateReturnsSameResult() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));

        JourneyOrderResult r1 = service.createOrder(request, "idem-same", "corr-1");
        JourneyOrderResult r2 = service.createOrder(request, "idem-same", "corr-1");

        assertEquals(r1.orderId(), r2.orderId());
        assertEquals(r1.monetarySummary(), r2.monetarySummary());

        // Only one event published (first call)
        assertEquals(1, published().size());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentCreateRequestThrows() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        var differentRequest = new JourneyOrderRequest("account-1", "offer-2", 1,
            List.of("tvl-1"), List.of("seg-1"));

        service.createOrder(request, "idem-reused", "corr-1");

        assertThrows(OrderManagementService.IdempotencyKeyReused.class, () ->
            service.createOrder(differentRequest, "idem-reused", "corr-1"));
    }

    @Test
    void idempotencyEntriesSurviveNewServiceInstanceWithSameStore() {
        InMemoryJourneyOrderStateRepository repository = new InMemoryJourneyOrderStateRepository();
        List<EventEnvelope> restartPublished = new ArrayList<>();
        EventPublisherAdapter publisher = restartPublished::add;
        OrderManagementService beforeRestart = new OrderManagementService(publisher, FIXED_CLOCK, repository);

        JourneyOrderRequest createRequest = new JourneyOrderRequest(
            "account-restart",
            "offer-restart",
            1,
            List.of("tvl-1"),
            List.of("seg-1")
        );
        JourneyOrderResult created = beforeRestart.createOrder(createRequest, "idem-restart-create", "corr-1");
        CancelJourneyOrderResult cancelled = beforeRestart.cancelOrder(
            new CancelJourneyOrderRequest(created.orderId(), "restart check"),
            "idem-restart-cancel",
            "corr-1"
        );
        restartPublished.clear();

        OrderManagementService afterRestart = new OrderManagementService(publisher, FIXED_CLOCK, repository);

        assertEquals(created.orderId(), afterRestart
            .createOrder(createRequest, "idem-restart-create", "corr-2")
            .orderId());
        assertEquals(cancelled, afterRestart.cancelOrder(
            new CancelJourneyOrderRequest(created.orderId(), "restart check"),
            "idem-restart-cancel",
            "corr-2"
        ));
        assertTrue(restartPublished.isEmpty());
    }

    @Test
    void paymentExpiredPersistsCancellationBeforeAck() {
        InMemoryJourneyOrderStateRepository repository = new InMemoryJourneyOrderStateRepository();
        OrderManagementService expiryService = new OrderManagementService(
            envelope -> published.add(envelope),
            FIXED_CLOCK,
            repository
        );
        JourneyOrderResult created = expiryService.createOrder(
            new JourneyOrderRequest("account-expiry", "offer-expiry", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-payment-expiry",
            "corr-1"
        );
        OrderManagementService.StoredOrder stored = repository.findOrder(created.orderId()).orElseThrow();
        stored.order().markBookingAndCapacityAccepted(
            Instant.parse("2026-07-05T10:01:00Z"),
            "cmd-test",
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0cc03"
        );
        stored.order().markPendingPayment(
            "initial-ticket-purchase",
            Instant.parse("2026-07-05T10:01:00Z"),
            "cmd-test",
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0cc03"
        );
        repository.saveOrder(stored.order(), stored.idempotencyKey());
        published.clear();

        EventSubscriber.HandlerResult result = expiryService.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0cc02",
            "PaymentExpired",
            Instant.parse("2026-07-05T10:02:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0cc03",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0cc01",
            "payment",
            1,
            Map.of("orderId", created.orderId(), "paymentIntentId", "intent-expiry")
        ));

        assertEquals(new EventSubscriber.Success(), result);
        assertEquals("CANCELLED", expiryService.getOrder(created.orderId()).orElseThrow().status());
        assertEquals(1, published.stream().filter(event -> event.eventType().equals("JourneyOrderCancelled")).count());
    }

    @Test
    void entitlementIssuedOnCancelledOrderAckSkipsWithoutStateChange() {
        InMemoryJourneyOrderStateRepository repository = new InMemoryJourneyOrderStateRepository();
        OrderManagementService orderService = new OrderManagementService(envelope -> published.add(envelope), FIXED_CLOCK, repository);
        JourneyOrderResult created = orderService.createOrder(
            new JourneyOrderRequest("account-entitlement", "offer-entitlement", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-entitlement-cancelled",
            "corr-1"
        );
        orderService.cancelOrder(
            new CancelJourneyOrderRequest(created.orderId(), "customer cancellation"),
            "idem-entitlement-cancelled-cancel",
            "corr-1"
        );
        int publishedBefore = published.size();

        EventSubscriber.HandlerResult result = orderService.handle(entitlementIssuedEvent(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ce01",
            created.orderId()
        ));

        assertEquals(new EventSubscriber.Success(), result);
        assertEquals("CANCELLED", orderService.getOrder(created.orderId()).orElseThrow().status());
        assertEquals(publishedBefore, published.size());
    }

    @Test
    void missingOrderEventAckSkips() {
        EventSubscriber.HandlerResult result = service.handle(entitlementIssuedEvent(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ce02",
            "ord-0194f2e0-7b3e-7610-8284-5c26e8b0ce03"
        ));

        assertEquals(new EventSubscriber.Success(), result);
    }

    @Test
    void dataAccessExceptionReturnsTransientError() {
        JourneyOrderStateRepositoryAdapter failingRepository = new JourneyOrderStateRepositoryAdapter() {
            @Override
            public boolean recordProcessedEvent(String eventId, String stream) {
                throw new TransientDataAccessResourceException("database unavailable");
            }
        };
        OrderManagementService orderService = new OrderManagementService(envelope -> published.add(envelope), FIXED_CLOCK, failingRepository);

        EventSubscriber.HandlerResult result = orderService.handle(entitlementIssuedEvent(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ce04",
            "ord-0194f2e0-7b3e-7610-8284-5c26e8b0ce05"
        ));

        assertTrue(result instanceof EventSubscriber.TransientError);
    }

    @Test
    void missingRequiredPayloadFieldReturnsFatalError() {
        EventSubscriber.HandlerResult result = service.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ce06",
            "EntitlementIssued",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0ce07",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0ce08",
            "entitlement-ticketing",
            1,
            Map.of("journeyOrderId", "ord-0194f2e0-7b3e-7610-8284-5c26e8b0ce09")
        ));

        assertTrue(result instanceof EventSubscriber.FatalError);
    }

    @Test
    void getOrderReturnsEmptyForMissing() {
        Optional<JourneyOrderResult> result = service.getOrder("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void getOrderReturnsCreatedOrder() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        JourneyOrderResult created = service.createOrder(request, "idem-get", "corr-1");

        Optional<JourneyOrderResult> found = service.getOrder(created.orderId());
        assertTrue(found.isPresent());
        assertEquals(created.orderId(), found.get().orderId());
        assertEquals("account-1", found.get().accountId());
    }

    @Test
    void listOrdersFiltersByAccount() {
        service.createOrder(
            new JourneyOrderRequest("account-1", "offer-1", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-list-a1", "corr-1");
        service.createOrder(
            new JourneyOrderRequest("account-2", "offer-2", 1, List.of("tvl-2"), List.of("seg-2")),
            "idem-list-a2", "corr-2");

        OrderListResult list = service.listOrders("account-1", null, 20, 0);
        assertEquals(1, list.total());
        assertEquals("account-1", list.items().getFirst().accountId());
    }

    @Test
    void cancelOrderChangesStatus() {
        var request = new JourneyOrderRequest("account-1", "offer-1", 1,
            List.of("tvl-1"), List.of("seg-1"));
        JourneyOrderResult created = service.createOrder(request, "idem-cancel", "corr-1");

        CancelJourneyOrderResult cancelled = service.cancelOrder(
            new CancelJourneyOrderRequest(created.orderId(), "change of plans"),
            "idem-cancel-2", "corr-1");

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(created.orderId(), cancelled.orderId());
        assertNotNull(cancelled.cancelledAt());
    }

    @Test
    void cancelOrderThrowsNotFound() {
        assertThrows(OrderManagementService.NotFoundException.class, () ->
            service.cancelOrder(
                new CancelJourneyOrderRequest("nonexistent", "reason"),
                "idem-nonexist", "corr-1"));
    }

    @Test
    void riskAssessmentAllowRecordsExplicitConfirmationCondition() {
        JourneyOrderResult created = service.createOrder(
            new JourneyOrderRequest("account-risk", "offer-risk", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-risk-allow", "corr-1");
        published.clear();

        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c501",
            "RiskAssessmentResult",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c502",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c503",
            "risk-compliance",
            1,
            Map.of(
                "assessmentId", "asmt-0194f2e0-7b3e-7610-8284-5c26e8b0c510",
                "subjectRef", created.orderId(),
                "scenario", "order_risk",
                "decision", "ALLOW",
                "policyVersion", "risk-policy-v1",
                "evidenceRef", "evid-0194f2e0-7b3e-7610-8284-5c26e8b0c511",
                "reasonCode", "LOW_RISK",
                "assessmentSnapshotHash", "hash-risk",
                "assessedAt", "2026-07-05T10:01:00Z"
            )
        );

        service.handle(envelope);

        var order = service.getOrder(created.orderId());
        assertTrue(order.isPresent());
        assertEquals("CREATED", order.get().status());
    }

    @Test
    void riskBlockAppliedCancelsOrderAndLiftedDoesNotReviveCancelledOrder() {
        JourneyOrderResult created = service.createOrder(
            new JourneyOrderRequest("account-risk", "offer-block", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-risk-block", "corr-1");
        published.clear();

        EventEnvelope block = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c601",
            "RiskBlockApplied",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c602",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c603",
            "risk-compliance",
            1,
            Map.of(
                "blockId", "blk-0194f2e0-7b3e-7610-8284-5c26e8b0c610",
                "subjectRef", created.orderId(),
                "scope", "ORDER",
                "reasonCode", "HIGH_RISK_SIGNAL",
                "policyVersion", "risk-policy-v1",
                "evidenceRef", "evid-0194f2e0-7b3e-7610-8284-5c26e8b0c611",
                "blockedAt", "2026-07-05T10:01:00Z"
            )
        );
        EventEnvelope lifted = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c604",
            "RiskBlockLifted",
            Instant.parse("2026-07-05T10:02:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c602",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c601",
            "risk-compliance",
            1,
            Map.of(
                "allowId", "alw-0194f2e0-7b3e-7610-8284-5c26e8b0c620",
                "subjectRef", created.orderId(),
                "scope", "ORDER",
                "reasonCode", "MANUAL_REVIEW_CLEARED",
                "policyVersion", "risk-policy-v1",
                "evidenceRef", "evid-0194f2e0-7b3e-7610-8284-5c26e8b0c621",
                "allowedAt", "2026-07-05T10:02:00Z"
            )
        );

        service.handle(block);
        service.handle(lifted);

        assertEquals("CANCELLED", service.getOrder(created.orderId()).get().status());
        assertEquals(1, published.stream().filter(event -> event.eventType().equals("JourneyOrderCancelled")).count());
    }


    @Test
    void unknownAccountProjectionAllowsOrderCreation() {
        JourneyOrderResult result = service.createOrder(
            new JourneyOrderRequest("acct-unknown", "offer-unknown", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-account-unknown", "corr-1");

        assertEquals("acct-unknown", result.accountId());
        assertEquals("CREATED", result.status());
    }

    @Test
    void frozenAndClosedAccountProjectionRejectsNewOrderCreationAndUnfreezeRestoresIt() {
        service.handle(accountEvent("evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca01", "AccountCreated", "acct-gated"));
        service.createOrder(
            new JourneyOrderRequest("acct-gated", "offer-before-freeze", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-account-before-freeze", "corr-1");

        service.handle(accountEvent("evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca02", "AccountFrozen", "acct-gated"));
        var frozen = assertThrows(com.trainticket.journeyorder.domain.DomainRuleViolation.class, () ->
            service.createOrder(
                new JourneyOrderRequest("acct-gated", "offer-frozen", 1, List.of("tvl-1"), List.of("seg-1")),
                "idem-account-frozen", "corr-1"));
        assertEquals(com.trainticket.platformkit.http.ApiErrorCode.DOMAIN_RULE_VIOLATION, frozen.code());

        service.handle(accountEvent("evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca03", "AccountUnfrozen", "acct-gated"));
        JourneyOrderResult afterUnfreeze = service.createOrder(
            new JourneyOrderRequest("acct-gated", "offer-after-unfreeze", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-account-after-unfreeze", "corr-1");
        assertEquals("CREATED", afterUnfreeze.status());

        service.handle(accountEvent("evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca04", "AccountClosed", "acct-gated"));
        assertThrows(com.trainticket.journeyorder.domain.DomainRuleViolation.class, () ->
            service.createOrder(
                new JourneyOrderRequest("acct-gated", "offer-closed", 1, List.of("tvl-1"), List.of("seg-1")),
                "idem-account-closed", "corr-1"));
    }

    @Test
    void nonGatingAccountEventsAreIgnoredSuccessfully() {
        List<String> eventIds = List.of(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca11",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca12",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0ca13"
        );
        List<String> eventTypes = List.of("SessionOpened", "SessionRevoked", "PreferenceUpdated");

        for (int i = 0; i < eventTypes.size(); i++) {
            EventSubscriber.HandlerResult result = service.handle(accountEvent(
                eventIds.get(i),
                eventTypes.get(i),
                "acct-gated"));

            assertEquals(new EventSubscriber.Success(), result);
        }
    }

    @Test
    void unknownEventTypeIsAckedAndSkipped() {
        EventSubscriber.HandlerResult result = service.handle(accountEvent(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0cb01",
            "UnknownAccountEvent",
            "acct-gated"));

        assertEquals(new EventSubscriber.Success(), result);
    }

    private static void assertNotNull(Object obj) {
        if (obj == null) throw new AssertionError("Expected non-null");
    }


    private static EventEnvelope entitlementIssuedEvent(String eventId, String orderId) {
        return new EventEnvelope(
            eventId,
            "EntitlementIssued",
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0ce99",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0ce98",
            "entitlement-ticketing",
            1,
            Map.of(
                "entitlementId", "ent-0194f2e0-7b3e-7610-8284-5c26e8b0ce10",
                "segmentBookingId", "sb-0194f2e0-7b3e-7610-8284-5c26e8b0ce11",
                "journeyOrderId", orderId,
                "travelerRef", "tvl-1",
                "segmentRef", "seg-1",
                "issuePurpose", "INITIAL",
                "credentialNo", "ticket-1",
                "credentialType", "E_TICKET",
                "issuedAt", "2026-07-05T10:01:00Z"
            )
        );
    }

    private static class JourneyOrderStateRepositoryAdapter extends InMemoryJourneyOrderStateRepository {
    }

    private static EventEnvelope accountEvent(String eventId, String eventType, String accountId) {
        return new EventEnvelope(
            eventId,
            eventType,
            Instant.parse("2026-07-05T10:01:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0ca99",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0ca98",
            "account",
            1,
            Map.of(
                "accountId", accountId,
                "reason", "test reason",
                "operator", "system",
                "closureRequestId", "clr_0194f2e0_7b3e_7610_8284_5c26e8b0ca97",
                "final", true,
                "occurredAt", "2026-07-05T10:01:00Z"
            )
        );
    }

    private List<EventEnvelope> published() {
        return List.copyOf(published);
    }

    private interface EventPublisherAdapter extends com.trainticket.journeyorder.application.port.out.EventPublisher {
    }

}
