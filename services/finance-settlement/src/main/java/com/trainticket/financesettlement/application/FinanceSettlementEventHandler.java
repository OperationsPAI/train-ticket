package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.ConsumedEventLog;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FinanceSettlementEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventLogRepository consumedEvents;
    private final PaymentIntentOrderReferenceRepository paymentIntentOrderReferences;
    private final ConcurrentMap<String, PaymentCaptureFact> capturesByOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Money> approvedRefundsByCaseId = new ConcurrentHashMap<>();
    private final Clock clock;
    private final FinanceSettlementApplicationService service;

    public FinanceSettlementEventHandler(ConsumedEventLogRepository consumedEvents, Clock clock) {
        this(consumedEvents, new InMemoryPaymentIntentOrderReferenceRepository(), clock, null);
    }

    public FinanceSettlementEventHandler(
        ConsumedEventLogRepository consumedEvents,
        PaymentIntentOrderReferenceRepository paymentIntentOrderReferences,
        Clock clock,
        FinanceSettlementApplicationService service
    ) {
        this.consumedEvents = consumedEvents;
        this.paymentIntentOrderReferences = paymentIntentOrderReferences;
        this.clock = clock;
        this.service = service;
    }

    @Override
    public HandlerResult handle(EventEnvelope envelope) {
        if (consumedEvents.existsByEventId(envelope.eventId())) {
            return HandlerResult.SUCCESS;
        }
        try {
            dispatch(envelope);
            consumedEvents.save(ConsumedEventLog.record(
                envelope.eventId(),
                envelope.producer(),
                envelope.eventType(),
                clock.instant()
            ));
            return HandlerResult.SUCCESS;
        } catch (PublishFailedException | OutOfOrderEventException ex) {
            return HandlerResult.TRANSIENT_FAILURE;
        } catch (DomainRuleViolation | IllegalArgumentException ex) {
            return HandlerResult.FATAL_FAILURE;
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(EventEnvelope envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        switch (envelope.eventType()) {
            case "PaymentIntentCreated" -> rememberPaymentIntentOrderReference(payload);
            case "PaymentCaptured" -> {
                if (service != null) recognizeCapturedPayment(envelope, payload);
            }
            case "ProviderReservationConfirmed", "SegmentBookingCancelled" -> {
                if (service != null) reconcileOperationalFact(envelope, payload);
            }
            case "PostSalesApproved" -> rememberApprovedRefund(payload);
            case "PostSalesApplied" -> {
                if (service != null) applyPostSales(envelope, payload);
            }
            default -> { }
        }
    }

    private void rememberPaymentIntentOrderReference(Map<String, Object> payload) {
        paymentIntentOrderReferences.save(text(payload, "paymentIntentId"), text(payload, "businessRef"));
    }

    private void recognizeCapturedPayment(EventEnvelope envelope, Map<String, Object> payload) {
        String paymentIntentId = text(payload, "paymentIntentId");
        String orderId = optionalText(payload, "businessRef")
            .or(() -> paymentIntentOrderReferences.findOrderReference(paymentIntentId))
            .orElseThrow(() -> new OutOfOrderEventException("PaymentCaptured received before order reference"));
        paymentIntentOrderReferences.save(paymentIntentId, orderId);
        Money captured = money(payload.get("capturedAmount"), "capturedAmount");
        capturesByOrderId.put(orderId, new PaymentCaptureFact(paymentIntentId, captured, envelope.eventId()));
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
        String orderId = optionalText(payload, "journeyOrderId")
            .or(() -> optionalText(payload, "orderId"))
            .orElse(optionalText(payload, "businessRef").orElse("order-unknown-for-" + text(payload, "segmentBookingId")));
        PaymentCaptureFact capture = capturesByOrderId.get(orderId);
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

    private void rememberApprovedRefund(Map<String, Object> payload) {
        String caseId = optionalText(payload, "caseId").orElse(optionalText(payload, "postSalesCaseId").orElse(null));
        if (caseId == null) return;
        Object approvedActions = payload.get("approvedActions");
        if (approvedActions instanceof Map<?, ?> actions && actions.get("refund") instanceof Map<?, ?> refund) {
            approvedRefundsByCaseId.put(caseId, money(((Map<?, ?>) refund).get("amount"), "approvedActions.refund.amount"));
        }
    }

    private void applyPostSales(EventEnvelope envelope, Map<String, Object> payload) {
        String orderId = text(payload, "orderId");
        String caseId = optionalText(payload, "caseId").orElse(optionalText(payload, "postSalesCaseId").orElse(""));
        Money refund = Optional.ofNullable(approvedRefundsByCaseId.get(caseId))
            .or(() -> Optional.ofNullable(approvedRefundsByCaseId.get(orderId)))
            .orElseThrow(() -> new OutOfOrderEventException("PostSalesApplied received before PostSalesApproved refund amount"));
        RevenueRecognition reduction = RevenueRecognition.recognize(
            orderId,
            optionalText(payload, "orderItemId").orElse(orderId),
            "refund",
            refund.negate(),
            "post-sales-refund-v1",
            envelope.eventId(),
            envelope.occurredAt(),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(reduction);
        PaymentCaptureFact capture = capturesByOrderId.get(orderId);
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
        return recognitions.stream().map(RevenueRecognition::amount).reduce(Money.zero(currency), Money::plus);
    }

    private static boolean sameMoney(Money left, Money right) {
        return left.currency().equals(right.currency()) && left.compareTo(right) == 0;
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

    private record PaymentCaptureFact(String paymentIntentId, Money amount, String sourceEventId) {}

    private static final class OutOfOrderEventException extends RuntimeException {
        private OutOfOrderEventException(String message) {
            super(message);
        }
    }
}
