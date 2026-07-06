package com.trainticket.journeyorder.application.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderService;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.port.out.JourneyOrderEventHandler;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.journeyorder.domain.JourneyOrder;
import com.trainticket.journeyorder.domain.JourneyOrderEvent;
import com.trainticket.journeyorder.domain.Money;
import com.trainticket.journeyorder.domain.MonetarySummary;
import com.trainticket.journeyorder.domain.OfferSnapshotRef;
import com.trainticket.journeyorder.domain.OrderItem;
import com.trainticket.journeyorder.domain.SegmentOrderSnapshot;
import com.trainticket.journeyorder.domain.TravelerRef;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderManagementService implements JourneyOrderService, JourneyOrderEventHandler {

    private final Map<String, StoredOrder> orderStore = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry<JourneyOrderResult>> createIdempotencyStore = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry<CancelJourneyOrderResult>> cancelIdempotencyStore = new LinkedHashMap<>();
    private final EventPublisher eventPublisher;
    private final Set<String> consumedEventIds = new HashSet<>();
    private final Clock clock;

    @Autowired
    public OrderManagementService(EventPublisher eventPublisher, Clock clock) {
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public OrderManagementService(EventPublisher eventPublisher) {
        this(eventPublisher, Clock.systemUTC());
    }

    @Override
    public JourneyOrderResult createOrder(JourneyOrderRequest request, String idempotencyKey, String correlationId) {
        String fingerprint = createFingerprint(request);
        IdempotencyEntry<JourneyOrderResult> existing = createIdempotencyStore.get(idempotencyKey);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReused("Idempotency-Key was reused with a different create order request");
            }
            return existing.result();
        }

        Instant now = Instant.now(clock);
        String sourceCommandId = "cmd-" + UUID.randomUUID();

        // Build domain objects from the simple request
        OfferSnapshotRef offerRef = new OfferSnapshotRef(
            request.offerId(), request.offerVersion(), now.minusSeconds(60),
            now.plusSeconds(600), "price-snap-" + request.offerId(), "rule-snap-" + request.offerId()
        );

        List<TravelerRef> travelers = request.travelerRefs().stream()
            .map(tid -> new TravelerRef(tid, "ADULT"))
            .toList();

        List<SegmentOrderSnapshot> segments = request.segmentRefs().stream()
            .map(sid -> new SegmentOrderSnapshot(sid, "ORIG", "DEST", "TRAIN",
                now.plusSeconds(86400), now.plusSeconds(111600), ""))
            .toList();

        // Create minimal order items for the aggregate
        List<OrderItem> orderItems = new ArrayList<>();
        for (int i = 0; i < travelers.size(); i++) {
            String itemId = "fare-" + request.offerId() + "-" + i;
            String travelerId = request.travelerRefs().get(i);
            String segmentRef = request.segmentRefs().get(0);
            orderItems.add(new OrderItem(
                itemId, com.trainticket.journeyorder.domain.OrderItemType.SEGMENT_FARE,
                "fare", Money.of("CNY", "100.00"), "segment-1",
                List.of(new com.trainticket.journeyorder.domain.OrderLineBinding(itemId, travelerId, segmentRef, ""))
            ));
        }

        JourneyOrder order = JourneyOrder.createFromOffer(
            request.accountId(), "api", request.offerId(),
            offerRef, travelers, segments, orderItems, now,
            sourceCommandId, correlationId
        );

        StoredOrder stored = new StoredOrder(order, idempotencyKey);
        orderStore.put(order.orderId(), stored);
        JourneyOrderResult result = toResult(order);
        createIdempotencyStore.put(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));
        publishEvents(order.domainEvents());

        return result;
    }

    @Override
    public Optional<JourneyOrderResult> getOrder(String orderId) {
        StoredOrder stored = orderStore.get(orderId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(toResult(stored.order));
    }

    @Override
    public OrderListResult listOrders(String accountId, String status, int limit, int offset) {
        List<JourneyOrderResult> all = orderStore.values().stream()
            .map(s -> toResult(s.order))
            .filter(r -> accountId == null || accountId.isBlank() || r.accountId().equals(accountId))
            .filter(r -> status == null || status.isBlank() || r.status().equals(status))
            .toList();

        int total = all.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + Math.min(limit, 100), total);
        List<JourneyOrderResult> items = all.subList(from, to);

        return new OrderListResult(items, total, limit, offset);
    }

    @Override
    public CancelJourneyOrderResult cancelOrder(CancelJourneyOrderRequest request, String idempotencyKey, String correlationId) {
        StoredOrder stored = orderStore.get(request.orderId());
        if (stored == null) {
            throw new NotFoundException("Order not found: " + request.orderId());
        }

        String fingerprint = cancelFingerprint(request);
        IdempotencyEntry<CancelJourneyOrderResult> existing = cancelIdempotencyStore.get(idempotencyKey);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReused("Idempotency-Key was reused with a different cancel order request");
            }
            return existing.result();
        }

        JourneyOrder order = stored.order;

        Instant now = Instant.now(clock);
        String sourceCommandId = "cmd-" + UUID.randomUUID();
        int eventCount = order.domainEvents().size();
        order.cancel(request.reason(), now, sourceCommandId, sourceCommandId, correlationId);

        List<JourneyOrderEvent> newEvents = order.domainEvents().subList(eventCount, order.domainEvents().size());
        CancelJourneyOrderResult result = new CancelJourneyOrderResult(order.orderId(), "CANCELLED", now);
        cancelIdempotencyStore.put(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));
        publishEvents(newEvents);
        return result;
    }

    private void publishEvents(List<JourneyOrderEvent> events) {
        for (JourneyOrderEvent event : events) {
            eventPublisher.publish(envelopeWithPayload(event));
        }
    }

    private static EventEnvelope envelopeWithPayload(JourneyOrderEvent event) {
        EventEnvelope envelope = event.envelope();
        return new EventEnvelope(
            envelope.eventId(),
            envelope.eventType(),
            envelope.occurredAt(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.producer(),
            envelope.schemaVersion(),
            eventPayload(event)
        );
    }

    private static Map<String, Object> eventPayload(JourneyOrderEvent event) {
        return switch (event) {
            case com.trainticket.journeyorder.domain.JourneyOrderCreated created -> Map.of(
                "orderId", created.orderId(),
                "accountId", created.accountId(),
                "offerId", created.offerId(),
                "monetarySummary", monetaryPayload(created.monetarySummary()),
                "travelerRefs", created.travelerRefs().stream().map(OrderManagementService::travelerPayload).toList(),
                "segmentRefs", created.segmentRefs(),
                "createdAt", created.createdAt().toString()
            );
            case com.trainticket.journeyorder.domain.JourneyOrderPendingPayment pending -> Map.of(
                "orderId", pending.orderId(),
                "accountId", pending.accountId(),
                "paymentPurpose", pending.paymentPurpose(),
                "monetarySummary", monetaryPayload(pending.monetarySummary())
            );
            case com.trainticket.journeyorder.domain.JourneyOrderPaymentRecorded payment -> Map.of(
                "orderId", payment.orderId(),
                "accountId", payment.accountId(),
                "paymentIntentId", payment.paymentIntentId()
            );
            case com.trainticket.journeyorder.domain.JourneyOrderConfirmed confirmed -> Map.of(
                "orderId", confirmed.orderId(),
                "accountId", confirmed.accountId(),
                "monetarySummary", monetaryPayload(confirmed.monetarySummary()),
                "confirmedAt", confirmed.confirmedAt().toString()
            );
            case com.trainticket.journeyorder.domain.JourneyOrderCancelled cancelled -> Map.of(
                "orderId", cancelled.orderId(),
                "accountId", cancelled.accountId(),
                "reason", cancelled.reason()
            );
            case com.trainticket.journeyorder.domain.JourneyOrderPostSalesAdjusted adjusted -> Map.of(
                "orderId", adjusted.orderId(),
                "accountId", adjusted.accountId(),
                "postSalesCaseId", adjusted.postSalesCaseId(),
                "monetarySummary", monetaryPayload(adjusted.monetarySummary())
            );
        };
    }

    private static Map<String, Object> travelerPayload(TravelerRef traveler) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("travelerId", traveler.travelerId());
        payload.put("travelerType", traveler.travelerType());
        if (traveler.maskedDocumentRef() != null && !traveler.maskedDocumentRef().isBlank()) {
            payload.put("maskedDocumentNo", traveler.maskedDocumentRef());
        }
        if (traveler.eligibilityRef() != null) {
            Map<String, Object> eligibility = new LinkedHashMap<>();
            eligibility.put("eligibilityId", traveler.eligibilityRef().eligibilityId());
            eligibility.put("eligibilityType", traveler.eligibilityRef().eligibilityType());
            eligibility.put("eligibilitySource", traveler.eligibilityRef().eligibilitySource());
            if (traveler.eligibilityRef().evidenceHash() != null) {
                eligibility.put("evidenceHash", traveler.eligibilityRef().evidenceHash());
            }
            if (traveler.eligibilityRef().verifiedAt() != null) {
                eligibility.put("verifiedAt", traveler.eligibilityRef().verifiedAt().toString());
            }
            payload.put("eligibilityRef", eligibility);
        }
        return payload;
    }

    private static Map<String, Object> monetaryPayload(MonetarySummary summary) {
        String currency = summary.currency().getCurrencyCode();
        return Map.of(
            "subtotal", moneyPayload(currency, summary.itemSubtotal().toMinorUnits()),
            "taxTotal", moneyPayload(currency, summary.taxTotal().toMinorUnits()),
            "feeTotal", moneyPayload(currency, summary.feeTotal().toMinorUnits()),
            "discountTotal", moneyPayload(currency, summary.discountTotal().toMinorUnits()),
            "cancelledTotal", moneyPayload(currency, summary.cancelledTotal().toMinorUnits()),
            "payableTotal", moneyPayload(currency, summary.payableTotal().toMinorUnits()),
            "total", moneyPayload(currency, summary.payableTotal().toMinorUnits()),
            "currency", currency
        );
    }

    private static Map<String, Object> moneyPayload(String currency, long minorUnits) {
        return Map.of("currency", currency, "minorUnits", minorUnits);
    }

    private static String createFingerprint(JourneyOrderRequest request) {
        return Objects.requireNonNull(request.accountId()) + "|"
            + Objects.requireNonNull(request.offerId()) + "|"
            + request.offerVersion() + "|"
            + String.join(",", request.travelerRefs()) + "|"
            + String.join(",", request.segmentRefs());
    }

    private static String cancelFingerprint(CancelJourneyOrderRequest request) {
        return Objects.requireNonNull(request.orderId()) + "|" + Objects.requireNonNull(request.reason());
    }

    private static String toApiStatus(JourneyOrder order) {
        return switch (order.state()) {
            case PENDING_CONFIRMATION -> "CREATED";
            case PENDING_PAYMENT -> "PENDING_PAYMENT";
            case CONFIRMED -> "CONFIRMED";
            case CANCELLED -> "CANCELLED";
            case POST_SALES_ADJUSTED -> "ADJUSTED";
            default -> order.state().name();
        };
    }

    private static JourneyOrderResult toResult(JourneyOrder order) {
        MonetarySummary ms = order.monetarySummary();
        return new JourneyOrderResult(
            order.orderId(),
            order.accountId(),
            order.offerSnapshot().offerId(),
            new JourneyOrderResult.MonetarySummaryDto(
                ms.currency().getCurrencyCode(),
                ms.itemSubtotal().toMinorUnits(),
                ms.taxTotal().toMinorUnits(),
                ms.feeTotal().toMinorUnits(),
                ms.discountTotal().toMinorUnits(),
                ms.cancelledTotal().toMinorUnits(),
                ms.payableTotal().toMinorUnits()
            ),
            toApiStatus(order),
            order.travelers().stream().map(TravelerRef::travelerId).toList(),
            order.segments().stream().map(SegmentOrderSnapshot::segmentRef).toList(),
            order.timeline().isEmpty() ? null : order.timeline().getFirst().occurredAt()
        );
    }

    @Override
    public synchronized EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!consumedEventIds.add(envelope.eventId())) {
            return new EventSubscriber.Success();
        }
        try {
            return switch (envelope.eventType()) {
                case "PaymentCaptured" -> handlePaymentCaptured(envelope);
                case "PaymentExpired" -> handlePaymentExpired(envelope);
                case "PostSalesApplied" -> handlePostSalesApplied(envelope);
                case "RiskBlockApplied" -> handleRiskBlockApplied(envelope);
                case "OfferExpired", "OfferQuoted", "TravelerProfileUpdated", "TravelerDocumentVerified", "TravelerEligibilityChanged", "RiskAssessmentResult", "RiskBlockLifted" -> new EventSubscriber.Success();
                default -> new EventSubscriber.FatalError("unsupported event type for journey-order: " + envelope.eventType());
            };
        } catch (RuntimeException ex) {
            consumedEventIds.remove(envelope.eventId());
            return new EventSubscriber.TransientError(ex.getMessage());
        }
    }

    private EventSubscriber.HandlerResult handlePaymentCaptured(EventEnvelope envelope) {
        JourneyOrder order = orderFromPayload(envelope);
        int eventCount = order.domainEvents().size();
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_CONFIRMATION) {
            order.markBookingAndCapacityAccepted(envelope.occurredAt(), "cmd-consume-payment", envelope.correlationId());
            order.markPendingPayment("initial-ticket-purchase", envelope.occurredAt(), "cmd-consume-payment", envelope.correlationId());
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_PAYMENT) {
            order.recordPaymentCaptured(textPayload(envelope, "paymentIntentId", "payment-intent-unknown"), envelope.occurredAt(), "cmd-consume-payment", envelope.eventId(), envelope.correlationId());
        }
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handlePaymentExpired(EventEnvelope envelope) {
        JourneyOrder order = orderFromPayload(envelope);
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_PAYMENT) {
            int eventCount = order.domainEvents().size();
            order.expirePayment(textPayload(envelope, "paymentIntentId", "payment-intent-unknown"), envelope.occurredAt(), "cmd-consume-payment", envelope.eventId(), envelope.correlationId());
            publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        }
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handlePostSalesApplied(EventEnvelope envelope) {
        JourneyOrder order = orderFromPayload(envelope);
        int eventCount = order.domainEvents().size();
        order.applyPostSalesItemCancellation(
            textPayload(envelope, "orderItemId", order.orderItems().getFirst().orderItemId()),
            textPayload(envelope, "postSalesCaseId", "post-sales-unknown"),
            textPayload(envelope, "reason", "post-sales applied"),
            envelope.occurredAt(),
            "cmd-consume-post-sales",
            envelope.eventId(),
            envelope.correlationId()
        );
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleRiskBlockApplied(EventEnvelope envelope) {
        JourneyOrder order = orderFromPayload(envelope);
        int eventCount = order.domainEvents().size();
        order.cancel(textPayload(envelope, "reason", "risk block applied"), envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private JourneyOrder orderFromPayload(EventEnvelope envelope) {
        String orderId = textPayload(envelope, "orderId", null);
        if (orderId == null) {
            throw new IllegalArgumentException("event payload missing orderId");
        }
        StoredOrder stored = orderStore.get(orderId);
        if (stored == null) {
            throw new NotFoundException("Order not found for consumed event: " + orderId);
        }
        return stored.order();
    }

    private static String textPayload(EventEnvelope envelope, String field, String fallback) {
        if (!(envelope.payload() instanceof Map<?, ?> payload)) {
            return fallback;
        }
        Object value = payload.get(field);
        return value == null ? fallback : String.valueOf(value);
    }

    private record StoredOrder(JourneyOrder order, String idempotencyKey) {}

    private record IdempotencyEntry<T>(String requestFingerprint, T result) {}

    public static final class IdempotencyKeyReused extends ApiException {
        public IdempotencyKeyReused(String message) {
            super(ApiErrorCode.IDEMPOTENCY_KEY_REUSED, message);
        }
    }

    public static final class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(ApiErrorCode.NOT_FOUND, message);
        }
    }
}
