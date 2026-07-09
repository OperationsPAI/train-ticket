package com.trainticket.financesettlement.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trainticket.financesettlement.domain.ConsumedEventLog;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

public class FinanceSettlementEventHandler implements EventSubscriber.EventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceSettlementEventHandler.class);
    private static final int ZERO_REFUND_WARNING_SAMPLE_RATE = 100;
    private static final AtomicInteger ZERO_REFUND_WARNING_SEQUENCE = new AtomicInteger();
    private final ConsumedEventLogRepository consumedEvents;
    private final PaymentIntentOrderReferenceRepository paymentIntentOrderReferences;
    private final SegmentBookingOrderReferenceRepository segmentBookingOrderReferences;
    private final FinanceSettlementProjectionRepository projections;
    private final Clock clock;
    private final FinanceSettlementApplicationService service;

    public FinanceSettlementEventHandler(ConsumedEventLogRepository consumedEvents, Clock clock) {
        this(
            consumedEvents,
            new InMemoryPaymentIntentOrderReferenceRepository(),
            new InMemorySegmentBookingOrderReferenceRepository(),
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            null
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this(
            consumedEvents,
            paymentIntentOrderReferences,
            new InMemorySegmentBookingOrderReferenceRepository(),
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            service
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this(
            consumedEvents,
            paymentIntentOrderReferences,
            segmentBookingOrderReferences,
            new InMemoryFinanceSettlementProjectionRepository(),
            clock,
            service
        );
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        SegmentBookingOrderReferenceRepository segmentBookingOrderReferences,
        FinanceSettlementProjectionRepository projections,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this.consumedEvents = consumedEvents;
        this.paymentIntentOrderReferences = paymentIntentOrderReferences;
        this.segmentBookingOrderReferences = segmentBookingOrderReferences;
        this.projections = projections;
        this.clock = clock;
        this.service = service;
    }

    @Override
    @Transactional
    public HandlerResult handle(EventEnvelope envelope) {
        validateEnvelopeIds(envelope);
        ConsumedEventLog log = ConsumedEventLog.record(envelope.eventId(), envelope.producer(), envelope.eventType(), clock.instant());
        boolean preRecorded = consumedEvents.guardsTransactionally();
        if (preRecorded && !consumedEvents.recordIfNew(log)) {
            return HandlerResult.SUCCESS;
        }
        if (!preRecorded && consumedEvents.existsByEventId(envelope.eventId())) {
            return HandlerResult.SUCCESS;
        }
        try {
            dispatch(envelope);
            if (!preRecorded) {
                consumedEvents.save(log);
            }
            return HandlerResult.SUCCESS;
        } catch (PublishFailedException | OutOfOrderEventException ex) {
            LOGGER.warn("service=finance-settlement eventId={} eventType={} exceptionClass={} exceptionMessage={} transient handling failure",
                envelope.eventId(), envelope.eventType(), ex.getClass().getName(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.TRANSIENT_FAILURE;
        } catch (DomainRuleViolation | IllegalArgumentException ex) {
            LOGGER.warn("service=finance-settlement eventId={} eventType={} exceptionClass={} exceptionMessage={} fatal handling failure",
                envelope.eventId(), envelope.eventType(), ex.getClass().getName(), ex.getMessage(), ex);
            rollbackCurrentTransactionIfActive();
            return HandlerResult.FATAL_FAILURE;
        }
    }

    private static void validateEnvelopeIds(EventEnvelope envelope) {
        PrefixedIds.requireEventId(envelope.eventId());
        PrefixedIds.requireCorrelationId(envelope.correlationId());
        if (envelope.causationId() != null) {
            PrefixedIds.requireCausationId(envelope.causationId());
        }
    }

    private static void rollbackCurrentTransactionIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // Unit tests may run without a Spring-managed transaction.
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(EventEnvelope envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        switch (envelope.eventType()) {
            case "PaymentIntentCreated" -> rememberPaymentIntentOrderReference(payload);
            case "SegmentReservationRequested" -> rememberSegmentBookingOrderReference(payload);
            case "PaymentCaptured" -> {
                LOGGER.info("service=finance-settlement PaymentCaptured eventId={} serviceWired={} projections={} serviceRepos={}",
                    envelope.eventId(), service != null, projections.getClass().getSimpleName(),
                    service == null ? "-" : service.describeWiring());
                if (service != null) recognizeCapturedPayment(envelope, payload);
            }
            case "ProviderReservationConfirmed", "SegmentBookingCancelled" -> {
                if (service != null) reconcileOperationalFact(envelope, payload);
            }
            case "PostSalesApproved" -> rememberApprovedRefund(payload);
            case "PostSalesApplied" -> {
                if (service != null) applyPostSales(envelope, payload);
            }
            case "BenefitIssued", "BenefitRedeemed", "BenefitRedemptionReversed", "BenefitRevoked", "BenefitExpired" -> recordBenefitCostEntry(envelope, payload);
            default -> {
                // Streams contain event types that do not affect finance settlement.
            }
        }
    }

    private void recordBenefitCostEntry(EventEnvelope envelope, Map<String, Object> payload) {
        String benefitId = text(payload, "benefitId");
        BenefitCostEntry previousEntry = projections.findLatestBenefitCostEntryForBenefit(benefitId).orElse(null);
        projections.saveBenefitCostEntry(new BenefitCostEntry(
            envelope.eventId(),
            benefitId,
            text(payload, "accountId"),
            optionalText(payload, "issuanceSource").orElse(previousEntry == null ? "UNKNOWN" : previousEntry.issuanceSource()),
            optionalText(payload, "caseId").orElse(previousEntry == null ? null : previousEntry.caseId()),
            benefitCostAmount(envelope.eventType(), payload),
            normalizedBenefitCostEventType(envelope.eventType()),
            benefitOccurredAt(envelope, payload)
        ));
    }

    private static Money benefitCostAmount(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "BenefitIssued" -> money(payload.get("issuedAmount"), "issuedAmount");
            case "BenefitRedeemed" -> money(payload.get("redeemedAmount"), "redeemedAmount");
            case "BenefitRedemptionReversed" -> money(payload.get("reversedAmount"), "reversedAmount").negate();
            case "BenefitRevoked" -> money(payload.get("revokedAmount"), "revokedAmount").negate();
            case "BenefitExpired" -> money(payload.get("expiredAmount"), "expiredAmount").negate();
            default -> throw new IllegalArgumentException("unsupported benefit cost event type " + eventType);
        };
    }

    private static String normalizedBenefitCostEventType(String eventType) {
        return switch (eventType) {
            case "BenefitRedemptionReversed" -> "BenefitReversed";
            default -> eventType;
        };
    }

    private static Instant benefitOccurredAt(EventEnvelope envelope, Map<String, Object> payload) {
        String timestampField = switch (envelope.eventType()) {
            case "BenefitIssued" -> "issuedAt";
            case "BenefitRedeemed" -> "redeemedAt";
            case "BenefitRedemptionReversed" -> "reversedAt";
            case "BenefitRevoked" -> "revokedAt";
            case "BenefitExpired" -> "expiredAt";
            default -> null;
        };
        if (timestampField == null) {
            return envelope.occurredAt();
        }
        return optionalText(payload, timestampField).map(Instant::parse).orElse(envelope.occurredAt());
    }

    private void rememberPaymentIntentOrderReference(Map<String, Object> payload) {
        paymentIntentOrderReferences.save(text(payload, "paymentIntentId"), text(payload, "businessRef"));
    }

    private void rememberSegmentBookingOrderReference(Map<String, Object> payload) {
        segmentBookingOrderReferences.save(text(payload, "segmentBookingId"), text(payload, "journeyOrderId"));
    }

    private void recognizeCapturedPayment(EventEnvelope envelope, Map<String, Object> payload) {
        String paymentIntentId = text(payload, "paymentIntentId");
        String orderId = optionalText(payload, "businessRef")
            .or(() -> paymentIntentOrderReferences.findOrderReference(paymentIntentId))
            .orElseThrow(() -> new OutOfOrderEventException("PaymentCaptured received before order reference"));
        paymentIntentOrderReferences.save(paymentIntentId, orderId);
        Money captured = money(payload.get("capturedAmount"), "capturedAmount");
        projections.saveCapture(orderId, new PaymentCaptureFact(paymentIntentId, captured, envelope.eventId()));
        RevenueRecognition recognition = RevenueRecognition.recognize(
            orderId,
            orderItemReference(payload, orderId),
            "fare",
            captured,
            "payment-capture-v1",
            envelope.eventId(),
            envelope.occurredAt(),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(recognition);
    }

    private void reconcileOperationalFact(EventEnvelope envelope, Map<String, Object> payload) {
        String segmentBookingId = optionalText(payload, "segmentBookingId").orElse("");
        Optional<String> orderReference = orderReferenceForOperationalFact(payload, segmentBookingId);
        if (orderReference.isEmpty()) {
            ReconciliationCase open = ReconciliationCase.open(
                "",
                "",
                "missing-in-platform",
                Money.zero(Currency.getInstance("CNY")),
                Money.zero(Currency.getInstance("CNY")),
                envelope.eventType() + " could not be assigned to an order; missing SegmentReservationRequested index for segmentBookingId " + segmentBookingId,
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }

        String orderId = orderReference.get();
        PaymentCaptureFact capture = projections.findCapture(orderId).orElse(null);
        Money actual = capture == null ? Money.zero(Currency.getInstance("CNY")) : capture.amount();
        List<RevenueRecognition> recognitions = serviceRevenue(orderId);
        Money expected = sumOrZero(recognitions, actual.currency());
        if (capture == null || !sameMoney(expected, actual)) {
            ReconciliationCase open = ReconciliationCase.open(
                orderId,
                capture == null ? "" : capture.paymentIntentId(),
                capture == null ? "missing-in-platform" : "amount-mismatch",
                expected,
                actual,
                envelope.eventType() + " did not match recognized revenue",
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }
        service.publishReconciliationCompleted(
            orderId,
            capture.paymentIntentId(),
            expected,
            actual,
            recognitions.stream().map(RevenueRecognition::revenueRecognitionId).toList(),
            List.of(capture.sourceEventId(), envelope.eventId()),
            "MATCHED",
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
    }

    private Optional<String> orderReferenceForOperationalFact(Map<String, Object> payload, String segmentBookingId) {
        Optional<String> explicitOrderReference = optionalText(payload, "journeyOrderId")
            .or(() -> optionalText(payload, "orderId"))
            .or(() -> optionalText(payload, "businessRef"));
        if (explicitOrderReference.isPresent()) {
            return explicitOrderReference;
        }
        if (segmentBookingId.isBlank()) {
            return Optional.empty();
        }
        return segmentBookingOrderReferences.findOrderReference(segmentBookingId);
    }

    private void rememberApprovedRefund(Map<String, Object> payload) {
        String caseId = optionalText(payload, "caseId").orElse(optionalText(payload, "postSalesCaseId").orElse(null));
        if (caseId == null) {
            return;
        }
        Object approvedActions = payload.get("approvedActions");
        if (approvedActions instanceof Map<?, ?> actions && actions.get("refund") instanceof Map<?, ?> refund) {
            Money refundAmount = money(((Map<?, ?>) refund).get("amount"), "approvedActions.refund.amount");
            projections.saveApprovedRefund(caseId, refundAmount);
            optionalText(payload, "orderId").ifPresent(orderId -> projections.saveApprovedRefund(orderId, refundAmount));
        }
    }

    private void applyPostSales(EventEnvelope envelope, Map<String, Object> payload) {
        String orderId = text(payload, "orderId");
        String caseId = optionalText(payload, "caseId").orElse(optionalText(payload, "postSalesCaseId").orElse(""));
        Money refund = projections.findApprovedRefund(caseId)
            .or(() -> projections.findApprovedRefund(orderId))
            .orElseThrow(() -> new OutOfOrderEventException("PostSalesApplied received before PostSalesApproved refund amount"));
        if (refund.isZero()) {
            if (shouldLogZeroRefundWarning()) {
                LOGGER.warn("service=finance-settlement eventId={} eventType={} orderId={} caseId={} zero refund amount; skipping revenue reversal",
                    envelope.eventId(), envelope.eventType(), orderId, caseId);
            }
            return;
        }
        PaymentCaptureFact capture = projections.findCapture(orderId).orElse(null);
        Optional<RevenueRecognition> matchingRecognition = serviceRevenue(orderId).stream()
            .filter(recognition -> !recognition.reversed())
            .filter(recognition -> sameCurrency(recognition.amount(), refund))
            .filter(recognition -> sameMoney(recognition.amount(), refund) || recognition.amount().compareTo(refund) >= 0)
            .findFirst();
        if (matchingRecognition.isEmpty()) {
            ReconciliationCase open = ReconciliationCase.open(
                orderId,
                capture == null ? "" : capture.paymentIntentId(),
                "refund-lag",
                refund.negate(),
                Money.zero(refund.currency()),
                "PostSalesApplied refund could not be matched to recognized revenue",
                clock.instant(),
                causationIdOrEventId(envelope),
                envelope.correlationId()
            );
            service.saveAndPublish(open);
            return;
        }
        RevenueRecognition originalRecognition = matchingRecognition.get();
        int firstUnpublishedEventIndex = originalRecognition.domainEvents().size();
        originalRecognition.reverse(
            "post-sales refund applied",
            envelope.eventId(),
            refund,
            clock.instant(),
            causationIdOrEventId(envelope),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(originalRecognition, firstUnpublishedEventIndex);
        Money expected = capture == null ? refund.negate() : capture.amount().minus(refund);
        service.publishReconciliationCompleted(
            orderId,
            capture == null ? "" : capture.paymentIntentId(),
            expected,
            expected,
            serviceRevenue(orderId).stream().map(RevenueRecognition::revenueRecognitionId).toList(),
            capture == null ? List.of(envelope.eventId()) : List.of(capture.sourceEventId(), envelope.eventId()),
            "MATCHED",
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
    }

    private List<RevenueRecognition> serviceRevenue(String orderId) {
        return service.findRevenueRecognitionsByOrderId(orderId);
    }

    private static Money sumOrZero(List<RevenueRecognition> recognitions, Currency currency) {
        return recognitions.stream().map(RevenueRecognition::netAmount).reduce(Money.zero(currency), Money::plus);
    }

    private static boolean sameMoney(Money left, Money right) {
        return sameCurrency(left, right) && left.compareTo(right) == 0;
    }

    private static boolean shouldLogZeroRefundWarning() {
        int sequence = ZERO_REFUND_WARNING_SEQUENCE.incrementAndGet();
        return sequence == 1 || sequence % ZERO_REFUND_WARNING_SAMPLE_RATE == 0;
    }

    private static boolean sameCurrency(Money left, Money right) {
        return left.currency().equals(right.currency());
    }

    private static String orderItemReference(Map<String, Object> payload, String orderId) {
        return optionalText(payload, "orderItemId").orElse(orderId);
    }

    private static String causationIdOrEventId(EventEnvelope envelope) {
        return envelope.causationId() == null ? envelope.eventId() : envelope.causationId();
    }

    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }

    private static Optional<String> optionalText(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        return value instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Money money(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        Map<String, Object> amount = (Map<String, Object>) raw;
        String currencyCode = text(amount, "currency");
        Object minorUnitsValue = amount.get("minorUnits");
        if (!(minorUnitsValue instanceof Number minorUnits)) {
            throw new IllegalArgumentException(fieldName + ".minorUnits is required");
        }
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        BigDecimal majorUnits = BigDecimal.valueOf(minorUnits.longValue()).movePointLeft(fractionDigits);
        return Money.of(currencyCode, majorUnits.toPlainString());
    }

    public record PaymentCaptureFact(String paymentIntentId, Money amount, String sourceEventId) {}

    private static final class OutOfOrderEventException extends RuntimeException {
        private OutOfOrderEventException(String message) {
            super(message);
        }
    }
}
