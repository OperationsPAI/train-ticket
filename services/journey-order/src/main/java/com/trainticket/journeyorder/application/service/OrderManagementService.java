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
import com.trainticket.journeyorder.application.port.out.IdentityVerificationPort;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.journeyorder.domain.AccountOrderGate;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.DomainRuleViolation;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class OrderManagementService implements JourneyOrderService, JourneyOrderEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderManagementService.class);

    private final JourneyOrderStateRepository stateRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final IdentityVerificationPort identityVerification;

    @Autowired
    public OrderManagementService(EventPublisher eventPublisher, Clock clock, JourneyOrderStateRepository stateRepository, IdentityVerificationPort identityVerification) {
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.stateRepository = stateRepository;
        this.identityVerification = identityVerification;
    }

    public OrderManagementService(EventPublisher eventPublisher, Clock clock, JourneyOrderStateRepository stateRepository) {
        this(eventPublisher, clock, stateRepository, (request, orderIntentId, idempotencyKey, correlationId) -> new IdentityVerificationPort.PreOrderCheckResult("PASS", "poc-disabled"));
    }

    public OrderManagementService(EventPublisher eventPublisher, Clock clock) {
        this(eventPublisher, clock, new InMemoryJourneyOrderStateRepository());
    }

    public OrderManagementService(EventPublisher eventPublisher) {
        this(eventPublisher, Clock.systemUTC(), new InMemoryJourneyOrderStateRepository());
    }

    @Override
    @Transactional
    public JourneyOrderResult createOrder(JourneyOrderRequest request, String idempotencyKey, String correlationId) {
        String fingerprint = createFingerprint(request);
        IdempotencyEntry<JourneyOrderResult> existing = stateRepository.findCreateIdempotency(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReused("Idempotency-Key was reused with a different create order request");
            }
            return existing.result();
        }

        AccountOrderGate.assertCanCreateOrder(request.accountId(), stateRepository.findAccountState(request.accountId()));
        String orderIntentId = orderIntentId(idempotencyKey, request);
        String identityIdempotencyKey = identityIdempotencyKey(idempotencyKey, request);
        IdentityVerificationPort.PreOrderCheckResult identityResult = identityVerification.preOrderCheck(request, orderIntentId, identityIdempotencyKey, correlationId);
        if (identityResult.rejected() || identityResult.manualReviewRequired()) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Identity verification is not passed for all travelers");
        }
        if (!identityResult.accepted()) {
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification did not return PASS");
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
            sourceCommandId, correlationId, request.sourceIp()
        );

        try {
            stateRepository.saveOrder(order, idempotencyKey);
            stateRepository.saveIdentityPreOrderCheckId(order.orderId(), identityResult.preOrderCheckId());
            JourneyOrderResult result = toResult(order);
            stateRepository.saveCreateIdempotency(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));
            publishEvents(order.domainEvents());
            return result;
        } catch (RuntimeException exception) {
            identityVerification.releasePreOrderCheck(identityResult.preOrderCheckId(), "ORDER_CREATE_FAILED", identityIdempotencyKey, correlationId);
            throw exception;
        }

    }

    @Override
    public Optional<JourneyOrderResult> getOrder(String orderId) {
        return stateRepository.findOrder(orderId).map(StoredOrder::order).map(OrderManagementService::toResult);
    }

    @Override
    public OrderListResult listOrders(String accountId, String status, int limit, int offset) {
        List<JourneyOrderResult> items = stateRepository.listOrders(accountId, status, Math.min(limit, 100), offset).stream()
            .map(StoredOrder::order)
            .map(OrderManagementService::toResult)
            .toList();
        int total = (int) stateRepository.countOrders(accountId, status);
        return new OrderListResult(items, total, limit, offset);
    }

    @Override
    @Transactional
    public CancelJourneyOrderResult cancelOrder(CancelJourneyOrderRequest request, String idempotencyKey, String correlationId) {
        StoredOrder stored = stateRepository.findOrder(request.orderId()).orElse(null);
        if (stored == null) {
            throw new NotFoundException("Order not found: " + request.orderId());
        }

        String fingerprint = cancelFingerprint(request);
        IdempotencyEntry<CancelJourneyOrderResult> existing = stateRepository.findCancelIdempotency(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReused("Idempotency-Key was reused with a different cancel order request");
            }
            return existing.result();
        }

        JourneyOrder order = stored.order;
        boolean shouldReleaseIdentity = shouldReleaseIdentityReservation(order);

        Instant now = Instant.now(clock);
        String sourceCommandId = "cmd-" + UUID.randomUUID();
        int eventCount = order.domainEvents().size();
        order.cancel(request.reason(), now, sourceCommandId, sourceCommandId, correlationId);

        List<JourneyOrderEvent> newEvents = order.domainEvents().subList(eventCount, order.domainEvents().size());
        CancelJourneyOrderResult result = new CancelJourneyOrderResult(order.orderId(), "CANCELLED", now);
        stateRepository.saveOrder(order, stored.idempotencyKey());
        stateRepository.saveCancelIdempotency(idempotencyKey, new IdempotencyEntry<>(fingerprint, result));
        publishEvents(newEvents);
        if (shouldReleaseIdentity) {
            releaseIdentityPreOrderCheck(order.orderId(), "ORDER_CANCELLED", idempotencyKey, correlationId);
        }
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
            case com.trainticket.journeyorder.domain.JourneyOrderCreated created -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("orderId", created.orderId());
                payload.put("accountId", created.accountId());
                payload.put("offerId", created.offerId());
                payload.put("monetarySummary", monetaryPayload(created.monetarySummary()));
                payload.put("travelerRefs", created.travelerRefs().stream().map(OrderManagementService::travelerPayload).toList());
                payload.put("segmentRefs", created.segmentRefs());
                payload.put("createdAt", created.createdAt().toString());
                if (created.sourceIp() != null && !created.sourceIp().isBlank()) {
                    payload.put("sourceIp", created.sourceIp());
                }
                yield payload;
            }
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

    private static String orderIntentId(String idempotencyKey, JourneyOrderRequest request) {
        return "oint-" + uuidFromMaterial("journey-order:intent:" + Objects.requireNonNull(idempotencyKey) + ":" + createFingerprint(request));
    }

    private static String identityIdempotencyKey(String idempotencyKey, JourneyOrderRequest request) {
        return uuidFromMaterial("journey-order:identity-pre-order:" + Objects.requireNonNull(idempotencyKey) + ":" + createFingerprint(request));
    }

    private static String uuidFromMaterial(String material) {
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x70);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        return new UUID(java.nio.ByteBuffer.wrap(digest, 0, 8).getLong(), java.nio.ByteBuffer.wrap(digest, 8, 8).getLong()).toString();
    }

    public static String toApiStatus(JourneyOrder order) {
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
    @Transactional
    public synchronized EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        try {
            if (stateRepository.isEventProcessed(envelope.eventId())) {
                return new EventSubscriber.Success();
            }
            EventSubscriber.HandlerResult result = switch (envelope.eventType()) {
                case "PaymentCaptured" -> handlePaymentCaptured(envelope);
                case "PaymentExpired" -> handlePaymentExpired(envelope);
                case "PostSalesApplied" -> handlePostSalesApplied(envelope);
                case "RiskAssessmentResult" -> handleRiskAssessmentResult(envelope);
                case "RiskBlockApplied" -> handleRiskBlockApplied(envelope);
                case "RiskBlockLifted" -> handleRiskBlockLifted(envelope);
                case "AccountCreated" -> handleAccountCreated(envelope);
                case "AccountFrozen" -> handleAccountFrozen(envelope);
                case "AccountUnfrozen" -> handleAccountUnfrozen(envelope);
                case "AccountClosureStarted" -> handleAccountClosureStarted(envelope);
                case "AccountClosed" -> handleAccountClosed(envelope);
                case "EntitlementIssued" -> handleEntitlementIssued(envelope);
                case "OfferExpired", "OfferQuoted",
                    "TravelerProfileUpdated", "TravelerSnapshotUpdated", "TravelerDocumentVerified", "TravelerEligibilityChanged",
                    "SessionOpened", "SessionRevoked", "PreferenceUpdated" -> new EventSubscriber.Success();
                default -> new EventSubscriber.Success();
            };
            stateRepository.recordProcessedEvent(envelope.eventId(), envelope.producer());
            return result;
        } catch (InvalidEventPayload ex) {
            return new EventSubscriber.FatalError(ex.getMessage());
        } catch (NotFoundException ex) {
            return ackSkipMissingOrder(envelope);
        } catch (DataAccessException ex) {
            LOGGER.error("DataAccessException handling {} eventId={}: {}", envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return new EventSubscriber.TransientError(ex.getMessage());
        } catch (RuntimeException ex) {
            LOGGER.error("RuntimeException handling {} eventId={}: {}", envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return new EventSubscriber.TransientError(ex.getMessage());
        }
    }

    private static void rollbackCurrentTransactionIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // Unit tests may run without a Spring-managed transaction.
        }
    }

    private EventSubscriber.HandlerResult handlePaymentCaptured(EventEnvelope envelope) {
        requirePayloadFields(envelope, "paymentIntentId", "businessRef", "capturedAmount", "channel", "channelTransactionId");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        int eventCount = order.domainEvents().size();
        if (!isPaymentCaptureApplicable(order)) {
            return ackSkipStateRace(envelope, order);
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_CONFIRMATION) {
            order.markBookingAndCapacityAccepted(envelope.occurredAt(), "cmd-consume-payment", envelope.correlationId());
            order.markPendingPayment("initial-ticket-purchase", envelope.occurredAt(), "cmd-consume-payment", envelope.correlationId());
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_PAYMENT) {
            order.recordPaymentCaptured(
                requiredTextPayload(envelope, "paymentIntentId"),
                envelope.occurredAt(),
                "cmd-consume-payment",
                envelope.eventId(),
                envelope.correlationId()
            );
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.CONFIRMING
            && order.confirmationConditions().canConfirm()) {
            order.confirm("payment-captured", envelope.occurredAt(), "cmd-consume-payment", envelope.eventId(), envelope.correlationId());
            confirmIdentityPreOrderCheck(order.orderId(), envelope.eventId(), envelope.correlationId());
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleAccountCreated(EventEnvelope envelope) {
        requirePayloadFields(envelope, "accountId", "occurredAt");
        stateRepository.saveAccountState(requiredTextPayload(envelope, "accountId"), AccountOrderState.ACTIVE);
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleAccountFrozen(EventEnvelope envelope) {
        requirePayloadFields(envelope, "accountId", "reason", "operator", "occurredAt");
        stateRepository.saveAccountState(requiredTextPayload(envelope, "accountId"), AccountOrderState.FROZEN);
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleAccountUnfrozen(EventEnvelope envelope) {
        requirePayloadFields(envelope, "accountId", "reason", "occurredAt");
        stateRepository.saveAccountState(requiredTextPayload(envelope, "accountId"), AccountOrderState.ACTIVE);
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleAccountClosureStarted(EventEnvelope envelope) {
        requirePayloadFields(envelope, "accountId", "closureRequestId", "occurredAt");
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleAccountClosed(EventEnvelope envelope) {
        requirePayloadFields(envelope, "accountId", "closureRequestId", "final", "occurredAt");
        stateRepository.saveAccountState(requiredTextPayload(envelope, "accountId"), AccountOrderState.CLOSED);
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleEntitlementIssued(EventEnvelope envelope) {
        requirePayloadFields(envelope, "entitlementId", "journeyOrderId", "segmentBookingId", "travelerRef", "segmentRef", "issuePurpose", "credentialNo", "credentialType", "issuedAt");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        int eventCount = order.domainEvents().size();
        if (isTerminal(order)) {
            return ackSkipStateRace(envelope, order);
        }
        try {
            order.recordEntitlementSummaryAccepted(envelope.occurredAt(), "cmd-consume-entitlement", envelope.eventId(), envelope.correlationId());
        } catch (DomainRuleViolation | IllegalStateException ex) {
            return ackSkipStateRace(envelope, order);
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.CONFIRMING
            && order.confirmationConditions().canConfirm()) {
            order.confirm("entitlement-issued", envelope.occurredAt(), "cmd-consume-entitlement", envelope.eventId(), envelope.correlationId());
            confirmIdentityPreOrderCheck(order.orderId(), envelope.eventId(), envelope.correlationId());
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handlePaymentExpired(EventEnvelope envelope) {
        requirePayloadFields(envelope, "paymentIntentId");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        boolean shouldReleaseIdentity = shouldReleaseIdentityReservation(order);
        if (order.state() != com.trainticket.journeyorder.domain.OrderLifecycleState.PENDING_PAYMENT) {
            return ackSkipStateRace(envelope, order);
        }
        int eventCount = order.domainEvents().size();
        order.expirePayment(
            requiredTextPayload(envelope, "paymentIntentId"),
            envelope.occurredAt(),
            "cmd-consume-payment",
            envelope.eventId(),
            envelope.correlationId()
        );
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        if (shouldReleaseIdentity) {
            releaseIdentityPreOrderCheck(order.orderId(), "PAYMENT_EXPIRED", envelope.eventId(), envelope.correlationId());
        }
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handlePostSalesApplied(EventEnvelope envelope) {
        requirePayloadFields(envelope, "caseId", "orderId", "resultSummary");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        int eventCount = order.domainEvents().size();
        try {
            order.applyPostSalesItemCancellation(
                textPayload(envelope, "orderItemId", order.orderItems().getFirst().orderItemId()),
                requiredTextPayload(envelope, "caseId"),
                textPayload(envelope, "reason", "post-sales applied"),
                envelope.occurredAt(),
                "cmd-consume-post-sales",
                envelope.eventId(),
                envelope.correlationId()
            );
        } catch (DomainRuleViolation | IllegalStateException ex) {
            return ackSkipStateRace(envelope, order);
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleRiskAssessmentResult(EventEnvelope envelope) {
        requirePayloadFields(envelope, "assessmentId", "subjectRef", "scenario", "decision", "policyVersion", "evidenceRef", "reasonCode", "assessmentSnapshotHash", "assessedAt");
        if (!"ALLOW".equals(requiredTextPayload(envelope, "decision"))) {
            return new EventSubscriber.Success();
        }
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        int eventCount = order.domainEvents().size();
        try {
            order.recordRiskAssessmentAllowed(envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
        } catch (DomainRuleViolation | IllegalStateException ex) {
            return ackSkipStateRace(envelope, order);
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.CONFIRMING
            && order.confirmationConditions().canConfirm()) {
            order.confirm("risk-assessment-allowed", envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
            confirmIdentityPreOrderCheck(order.orderId(), envelope.eventId(), envelope.correlationId());
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleRiskBlockApplied(EventEnvelope envelope) {
        requirePayloadFields(envelope, "blockId", "subjectRef", "scope", "reasonCode", "policyVersion", "evidenceRef", "blockedAt");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        boolean shouldReleaseIdentity = shouldReleaseIdentityReservation(order);
        int eventCount = order.domainEvents().size();
        try {
            order.cancel(requiredTextPayload(envelope, "reasonCode"), envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
        } catch (DomainRuleViolation | IllegalStateException ex) {
            return ackSkipStateRace(envelope, order);
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        if (shouldReleaseIdentity) {
            releaseIdentityPreOrderCheck(order.orderId(), requiredTextPayload(envelope, "reasonCode"), envelope.eventId(), envelope.correlationId());
        }
        return new EventSubscriber.Success();
    }

    private EventSubscriber.HandlerResult handleRiskBlockLifted(EventEnvelope envelope) {
        requirePayloadFields(envelope, "allowId", "subjectRef", "scope", "reasonCode", "policyVersion", "evidenceRef", "allowedAt");
        StoredOrder stored = storedOrderFromPayload(envelope);
        JourneyOrder order = stored.order();
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.CANCELLED) {
            return ackSkipStateRace(envelope, order);
        }
        int eventCount = order.domainEvents().size();
        try {
            order.recordRiskBlockLifted(envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
        } catch (DomainRuleViolation | IllegalStateException ex) {
            return ackSkipStateRace(envelope, order);
        }
        if (order.state() == com.trainticket.journeyorder.domain.OrderLifecycleState.CONFIRMING
            && order.confirmationConditions().canConfirm()) {
            order.confirm("risk-block-lifted", envelope.occurredAt(), "cmd-consume-risk", envelope.eventId(), envelope.correlationId());
            confirmIdentityPreOrderCheck(order.orderId(), envelope.eventId(), envelope.correlationId());
        }
        stateRepository.saveOrder(order, stored.idempotencyKey());
        publishEvents(order.domainEvents().subList(eventCount, order.domainEvents().size()));
        return new EventSubscriber.Success();
    }


    private void confirmIdentityPreOrderCheck(String orderId, String causationId, String correlationId) {
        stateRepository.findIdentityPreOrderCheckId(orderId)
            .ifPresent(preOrderCheckId -> identityVerification.confirmPreOrderCheck(
                preOrderCheckId,
                orderId,
                uuidFromMaterial("journey-order:identity-confirm:" + orderId + ":" + causationId),
                correlationId
            ));
    }

    private void releaseIdentityPreOrderCheck(String orderId, String releaseReason, String causationId, String correlationId) {
        stateRepository.findIdentityPreOrderCheckId(orderId)
            .ifPresent(preOrderCheckId -> identityVerification.releasePreOrderCheck(
                preOrderCheckId,
                releaseReason,
                uuidFromMaterial("journey-order:identity-release:" + orderId + ":" + causationId),
                correlationId
            ));
    }

    private static boolean shouldReleaseIdentityReservation(JourneyOrder order) {
        return switch (order.state()) {
            case PENDING_CONFIRMATION, PENDING_PAYMENT, CONFIRMING -> true;
            default -> false;
        };
    }

    private StoredOrder storedOrderFromPayload(EventEnvelope envelope) {
        String orderId = textPayload(envelope, "orderId", null);
        if (orderId == null) {
            orderId = textPayload(envelope, "subjectRef", null);
        }
        if (orderId == null) {
            orderId = textPayload(envelope, "businessRef", null);
        }
        if (orderId == null) {
            orderId = textPayload(envelope, "journeyOrderId", null);
        }
        if (orderId == null || orderId.isBlank()) {
            throw new InvalidEventPayload("event payload missing orderId/businessRef");
        }
        return storedOrderFromId(orderId);
    }

    private StoredOrder storedOrderFromId(String orderId) {
        return stateRepository.findOrder(orderId)
            .orElseThrow(() -> new NotFoundException("Order not found for consumed event: " + orderId));
    }


    private EventSubscriber.HandlerResult ackSkipMissingOrder(EventEnvelope envelope) {
        LOGGER.warn("ack-skip journey-order event={} eventId={} orderId={} reason=ORDER_NOT_FOUND",
            envelope.eventType(), envelope.eventId(), orderIdForLog(envelope));
        return new EventSubscriber.Success();
    }

    private static EventSubscriber.HandlerResult ackSkipStateRace(EventEnvelope envelope, JourneyOrder order) {
        LOGGER.warn("ack-skip journey-order event={} eventId={} orderId={} currentStatus={} reason=STATE_RACE_OR_RULE_NOOP",
            envelope.eventType(), envelope.eventId(), order.orderId(), order.state());
        return new EventSubscriber.Success();
    }

    private static boolean isTerminal(JourneyOrder order) {
        return switch (order.state()) {
            case CANCELLED, COMPLETED, DISRUPTED, FAILED -> true;
            default -> false;
        };
    }

    private static boolean isPaymentCaptureApplicable(JourneyOrder order) {
        return switch (order.state()) {
            case PENDING_CONFIRMATION, PENDING_PAYMENT, CONFIRMING -> true;
            default -> false;
        };
    }

    private static void requirePayloadFields(EventEnvelope envelope, String... fields) {
        for (String field : fields) {
            requiredTextPayload(envelope, field);
        }
    }

    private static String requiredTextPayload(EventEnvelope envelope, String field) {
        String value = textPayload(envelope, field, null);
        if (value == null || value.isBlank()) {
            throw new InvalidEventPayload("event payload missing " + field);
        }
        return value;
    }

    private static String orderIdForLog(EventEnvelope envelope) {
        String orderId = textPayload(envelope, "orderId", null);
        if (orderId == null) {
            orderId = textPayload(envelope, "subjectRef", null);
        }
        if (orderId == null) {
            orderId = textPayload(envelope, "businessRef", null);
        }
        if (orderId == null) {
            orderId = textPayload(envelope, "journeyOrderId", null);
        }
        return orderId == null ? "unknown" : orderId;
    }

    private static String textPayload(EventEnvelope envelope, String field, String fallback) {
        if (!(envelope.payload() instanceof Map<?, ?> payload)) {
            return fallback;
        }
        Object value = payload.get(field);
        return value == null ? fallback : String.valueOf(value);
    }

    public record StoredOrder(JourneyOrder order, String idempotencyKey) {}

    public record IdempotencyEntry<T>(String requestFingerprint, T result) {}

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

    private static final class InvalidEventPayload extends RuntimeException {
        private InvalidEventPayload(String message) {
            super(message);
        }
    }
}
