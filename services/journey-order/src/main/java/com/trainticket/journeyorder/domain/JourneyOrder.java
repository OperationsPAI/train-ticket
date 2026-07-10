package com.trainticket.journeyorder.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JourneyOrder {
    private final String orderId;
    private final String accountId;
    private final String channelRef;
    private final String idempotencyKey;
    private final OfferSnapshotRef offerSnapshot;
    private final List<TravelerRef> travelers;
    private final List<SegmentOrderSnapshot> segments;
    private final List<OrderItem> orderItems;
    private final List<TimelineFact> timeline;
    private final List<JourneyOrderEvent> domainEvents;
    private OrderLifecycleState state;
    private MonetarySummary monetarySummary;
    private ConfirmationConditions confirmationConditions;
    private long version;

    @JsonCreator
    public JourneyOrder(
        String orderId,
        String accountId,
        String channelRef,
        String idempotencyKey,
        OfferSnapshotRef offerSnapshot,
        List<TravelerRef> travelers,
        List<SegmentOrderSnapshot> segments,
        List<OrderItem> orderItems
    ) {
        this.orderId = requireText(orderId, "orderId");
        this.accountId = requireText(accountId, "accountId");
        this.channelRef = requireText(channelRef, "channelRef");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.offerSnapshot = Objects.requireNonNull(offerSnapshot, "offerSnapshot is required");
        this.travelers = List.copyOf(Objects.requireNonNull(travelers, "travelers are required"));
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments are required"));
        this.orderItems = new ArrayList<>(Objects.requireNonNull(orderItems, "orderItems are required"));
        this.timeline = new ArrayList<>();
        this.domainEvents = new ArrayList<>();
        this.state = OrderLifecycleState.PENDING_CONFIRMATION;
        this.confirmationConditions = ConfirmationConditions.none();
        requireCreateInvariants();
        this.monetarySummary = MonetarySummary.fromItems(this.orderItems);
    }

    public static JourneyOrder createFromOffer(
        String accountId,
        String channelRef,
        String clientRequestId,
        OfferSnapshotRef offerSnapshot,
        List<TravelerRef> travelers,
        List<SegmentOrderSnapshot> segments,
        List<OrderItem> orderItems,
        Instant now,
        String sourceCommandId,
        String correlationId,
        String sourceIp
    ) {
        Objects.requireNonNull(offerSnapshot, "offerSnapshot is required").requireValidAt(now);
        JourneyOrder order = new JourneyOrder(
            "ord-" + com.trainticket.platformkit.idempotency.UuidV7.generate(),
            accountId,
            channelRef,
            accountId + ":" + offerSnapshot.offerId() + ":" + requireText(clientRequestId, "clientRequestId"),
            offerSnapshot,
            travelers,
            segments,
            orderItems
        );
        order.recordTimeline("JourneyOrderCreated", now, "journey-order", "valid offer accepted", Map.of("offerId", offerSnapshot.offerId()));
        order.domainEvents.add(new JourneyOrderCreated(
            createEnvelope("JourneyOrderCreated", now, sourceCommandId, correlationId),
            order.orderId,
            order.accountId,
            offerSnapshot.offerId(),
            order.monetarySummary,
            order.travelers,
            order.segments.stream().map(SegmentOrderSnapshot::segmentRef).toList(),
            now,
            blankToNull(sourceIp)
        ));
        return order;
    }

    public static JourneyOrder rehydrate(
        String orderId,
        String accountId,
        String channelRef,
        String idempotencyKey,
        OfferSnapshotRef offerSnapshot,
        List<TravelerRef> travelers,
        List<SegmentOrderSnapshot> segments,
        List<OrderItem> orderItems,
        OrderLifecycleState state,
        ConfirmationConditions confirmationConditions,
        List<TimelineFact> timeline
    ) {
        JourneyOrder order = new JourneyOrder(orderId, accountId, channelRef, idempotencyKey, offerSnapshot, travelers, segments, orderItems);
        order.state = Objects.requireNonNull(state, "state is required");
        order.confirmationConditions = Objects.requireNonNull(confirmationConditions, "confirmationConditions are required");
        order.monetarySummary = MonetarySummary.fromItems(order.orderItems);
        order.timeline.clear();
        order.timeline.addAll(Objects.requireNonNull(timeline, "timeline is required"));
        return order;
    }

    public JourneyOrder withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

    public String orderId() { return orderId; }
    public String accountId() { return accountId; }
    public String channelRef() { return channelRef; }
    public String idempotencyKey() { return idempotencyKey; }
    public OfferSnapshotRef offerSnapshot() { return offerSnapshot; }
    public List<TravelerRef> travelers() { return travelers; }
    public List<SegmentOrderSnapshot> segments() { return segments; }
    public List<OrderItem> orderItems() { return List.copyOf(orderItems); }
    public List<TimelineFact> timeline() { return List.copyOf(timeline); }
    public OrderLifecycleState state() { return state; }
    public MonetarySummary monetarySummary() { return monetarySummary; }
    public ConfirmationConditions confirmationConditions() { return confirmationConditions; }
    public long version() { return version; }
    @JsonIgnore
    public List<JourneyOrderEvent> domainEvents() { return List.copyOf(domainEvents); }

    public void markBookingAndCapacityAccepted(Instant occurredAt, String sourceCommandId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_CONFIRMATION);
        confirmationConditions = confirmationConditions.withBookingSummaryAccepted().withCapacitySummaryAccepted();
        recordTimeline("BookingCapacitySummaryAccepted", occurredAt, "booking-orchestration", "required booking and capacity summaries accepted", Map.of());
    }

    public void markPendingPayment(String paymentPurpose, Instant occurredAt, String sourceCommandId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_CONFIRMATION);
        if (!confirmationConditions.bookingSummaryAccepted() || !confirmationConditions.capacitySummaryAccepted()) {
            throw new DomainRuleViolation("pending payment requires accepted booking and capacity summary facts");
        }
        state = OrderLifecycleState.PENDING_PAYMENT;
        recordTimeline("JourneyOrderPendingPayment", occurredAt, "journey-order", "waiting for payment", Map.of("paymentPurpose", paymentPurpose));
        domainEvents.add(new JourneyOrderPendingPayment(
            createEnvelope("JourneyOrderPendingPayment", occurredAt, sourceCommandId, correlationId),
            orderId, accountId, paymentPurpose, monetarySummary
        ));
    }

    public void recordPaymentCaptured(String paymentIntentId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_PAYMENT);
        confirmationConditions = confirmationConditions.withPaymentConditionSatisfied();
        state = OrderLifecycleState.CONFIRMING;
        recordTimeline("PaymentCaptured", occurredAt, "payment", "payment condition satisfied; awaiting entitlement summary", Map.of("paymentIntentId", paymentIntentId));
        domainEvents.add(new JourneyOrderPaymentRecorded(
            createEnvelope("JourneyOrderPaymentRecorded", occurredAt, causationId, correlationId),
            orderId, accountId, paymentIntentId
        ));
    }

    public void recordRiskAssessmentAllowed(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_CONFIRMATION, OrderLifecycleState.PENDING_PAYMENT, OrderLifecycleState.CONFIRMING);
        confirmationConditions = confirmationConditions.withRiskCleared();
        recordTimeline("RiskAssessmentAllowed", occurredAt, "risk-compliance", "required risk assessment allowed order continuation", Map.of());
    }

    public void recordRiskBlockLifted(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_CONFIRMATION, OrderLifecycleState.PENDING_PAYMENT, OrderLifecycleState.CONFIRMING);
        confirmationConditions = confirmationConditions.withRiskCleared();
        recordTimeline("RiskBlockLifted", occurredAt, "risk-compliance", "risk block lifted for order", Map.of());
    }

    public void recordEntitlementSummaryAccepted(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        // Streams are only ordered per-producer; the entitlement fact may
        // arrive before the payment fact, so accept it in any pre-confirmed state.
        requireState(OrderLifecycleState.PENDING_CONFIRMATION, OrderLifecycleState.PENDING_PAYMENT, OrderLifecycleState.CONFIRMING);
        confirmationConditions = confirmationConditions.withEntitlementSummaryAccepted();
        recordTimeline("EntitlementSummaryAccepted", occurredAt, "entitlement-ticketing", "required entitlement summary accepted", Map.of());
    }

    public void confirm(String confirmationAttempt, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireState(OrderLifecycleState.CONFIRMING);
        if (!confirmationConditions.canConfirm()) {
            throw new DomainRuleViolation("cannot confirm JourneyOrder without accepted booking, capacity, payment, entitlement and risk facts");
        }
        state = OrderLifecycleState.CONFIRMED;
        recordTimeline("JourneyOrderConfirmed", occurredAt, "journey-order", "all confirmation conditions satisfied", Map.of("confirmationAttempt", confirmationAttempt));
        domainEvents.add(new JourneyOrderConfirmed(
            createEnvelope("JourneyOrderConfirmed", occurredAt, causationId, correlationId),
            orderId, accountId, monetarySummary, occurredAt
        ));
    }

    public void cancel(String reason, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (state == OrderLifecycleState.COMPLETED) {
            throw new DomainRuleViolation("completed JourneyOrder cannot be cancelled");
        }
        if (state == OrderLifecycleState.CANCELLED) {
            return;
        }
        state = OrderLifecycleState.CANCELLED;
        recordTimeline("JourneyOrderCancelled", occurredAt, "journey-order", reason, Map.of());
        domainEvents.add(new JourneyOrderCancelled(
            createEnvelope("JourneyOrderCancelled", occurredAt, causationId, correlationId),
            orderId, accountId, reason
        ));
    }

    public void expirePayment(String paymentIntentId, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireState(OrderLifecycleState.PENDING_PAYMENT);
        cancel("payment expired: " + paymentIntentId, occurredAt, sourceCommandId, causationId, correlationId);
    }

    public void applyPostSalesItemCancellation(String orderItemId, String postSalesCaseId, String reason, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (state != OrderLifecycleState.CONFIRMED && state != OrderLifecycleState.POST_SALES_ADJUSTED && state != OrderLifecycleState.IN_TRAVEL) {
            throw new DomainRuleViolation("post-sales item cancellation requires confirmed, adjusted, or in-travel order");
        }
        OrderItem item = orderItems.stream()
            .filter(candidate -> candidate.orderItemId().equals(orderItemId))
            .findFirst()
            .orElseThrow(() -> new DomainRuleViolation("unknown order item " + orderItemId));
        item.cancel(reason);
        monetarySummary = MonetarySummary.fromItems(orderItems);
        state = OrderLifecycleState.POST_SALES_ADJUSTED;
        recordTimeline("PostSalesAdjustmentApplied", occurredAt, "post-sales", reason, Map.of("postSalesCaseId", postSalesCaseId, "orderItemId", orderItemId));
        domainEvents.add(new JourneyOrderPostSalesAdjusted(
            createEnvelope("JourneyOrderPostSalesAdjusted", occurredAt, causationId, correlationId),
            orderId, accountId, postSalesCaseId, monetarySummary
        ));
    }

    private void requireCreateInvariants() {
        if (travelers.isEmpty()) {
            throw new DomainRuleViolation("JourneyOrder requires at least one traveler snapshot");
        }
        if (segments.isEmpty()) {
            throw new DomainRuleViolation("JourneyOrder requires at least one segment snapshot");
        }
        if (orderItems.isEmpty()) {
            throw new DomainRuleViolation("JourneyOrder requires at least one order item");
        }
        Set<String> travelerIds = new HashSet<>();
        for (TravelerRef traveler : travelers) {
            if (!travelerIds.add(traveler.travelerId())) {
                throw new DomainRuleViolation("duplicate traveler " + traveler.travelerId());
            }
        }
        Set<String> segmentRefs = new HashSet<>();
        for (SegmentOrderSnapshot segment : segments) {
            if (!segmentRefs.add(segment.segmentRef())) {
                throw new DomainRuleViolation("duplicate segment " + segment.segmentRef());
            }
        }
        Set<String> itemIds = new HashSet<>();
        for (OrderItem item : orderItems) {
            if (!itemIds.add(item.orderItemId())) {
                throw new DomainRuleViolation("duplicate order item " + item.orderItemId());
            }
            for (OrderLineBinding binding : item.bindings()) {
                if (!binding.orderItemId().equals(item.orderItemId())) {
                    throw new DomainRuleViolation("binding item id must match owner item id");
                }
                binding.requireKnownRefs(travelerIds, segmentRefs);
            }
        }
    }

    private void requireState(OrderLifecycleState... expected) {
        for (OrderLifecycleState candidate : expected) {
            if (state == candidate) {
                return;
            }
        }
        throw new DomainRuleViolation("expected order state " + java.util.Arrays.toString(expected) + " but was " + state);
    }

    private void recordTimeline(String type, Instant occurredAt, String actor, String reason, Map<String, String> attributes) {
        timeline.add(TimelineFact.of(type, occurredAt, actor, reason, attributes));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
    private static EventEnvelope createEnvelope(String eventType, Instant occurredAt, String causationId, String correlationId) {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            occurredAt,
            canonicalCorrelationId(correlationId),
            canonicalCausationId(causationId),
            "journey-order",
            1,
            java.util.Map.of()
        );
    }

    private static String canonicalCorrelationId(String correlationId) {
        return PrefixedIds.isCorrelationId(correlationId)
            ? correlationId
            : PrefixedIds.newCorrelationId();
    }

    private static String canonicalCausationId(String causationId) {
        if (PrefixedIds.isCausationId(causationId)) {
            return causationId;
        }
        return PrefixedIds.newCommandId();
    }

}
