package com.trainticket.journeyorder.application.service;

import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderService;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.domain.EventEnvelope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderManagementService implements JourneyOrderService {

    private final Map<String, StoredOrder> orderStore = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry<JourneyOrderResult>> createIdempotencyStore = new LinkedHashMap<>();
    private final Map<String, IdempotencyEntry<CancelJourneyOrderResult>> cancelIdempotencyStore = new LinkedHashMap<>();
    private final EventPublisher eventPublisher;
    private final Clock clock;

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

        for (JourneyOrderEvent event : order.domainEvents()) {
            eventPublisher.publish(envelopeWithPayload(event));
        }

        StoredOrder stored = new StoredOrder(order, idempotencyKey);
        orderStore.put(order.orderId(), stored);
        JourneyOrderResult result = toResult(order);
        createIdempotencyStore.put(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));

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
        order.cancel(request.reason(), now, sourceCommandId, sourceCommandId, correlationId);

        for (JourneyOrderEvent event : order.domainEvents()) {
            if (event instanceof com.trainticket.journeyorder.domain.JourneyOrderCancelled) {
                eventPublisher.publish(envelopeWithPayload(event));
            }
        }

        CancelJourneyOrderResult result = new CancelJourneyOrderResult(order.orderId(), "CANCELLED", now);
        cancelIdempotencyStore.put(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));
        return result;
    }

    private static EventEnvelope envelopeWithPayload(JourneyOrderEvent event) {
        return event.envelope().withPayload(eventPayload(event));
    }

    private static Map<String, Object> eventPayload(JourneyOrderEvent event) {
        return switch (event) {
            case com.trainticket.journeyorder.domain.JourneyOrderCreated created -> Map.of(
                "orderId", created.orderId(),
                "accountId", created.accountId(),
                "offerId", created.offerId(),
                "monetarySummary", monetaryPayload(created.monetarySummary())
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
                "monetarySummary", monetaryPayload(confirmed.monetarySummary())
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
            order.state().name(),
            order.travelers().stream().map(TravelerRef::travelerId).toList(),
            order.segments().stream().map(SegmentOrderSnapshot::segmentRef).toList(),
            order.timeline().isEmpty() ? null : order.timeline().getFirst().occurredAt()
        );
    }

    private record StoredOrder(JourneyOrder order, String idempotencyKey) {}

    private record IdempotencyEntry<T>(String requestFingerprint, T result) {}

    public static final class IdempotencyKeyReused extends RuntimeException {
        public IdempotencyKeyReused(String message) {
            super(message);
        }
    }

    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
