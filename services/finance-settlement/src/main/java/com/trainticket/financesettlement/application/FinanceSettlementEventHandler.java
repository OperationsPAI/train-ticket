package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.financesettlement.domain.ConsumedEventLog;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Map;

public class FinanceSettlementEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventLogRepository consumedEvents;
    private final PaymentIntentOrderReferenceRepository paymentIntentOrderReferences;
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

    private void dispatch(EventEnvelope envelope) {
        if ("PaymentIntentCreated".equals(envelope.eventType())) {
            rememberPaymentIntentOrderReference((Map<String, Object>) envelope.payload());
            return;
        }
        if (service != null && "PaymentCaptured".equals(envelope.eventType())) {
            recognizeCapturedPayment(envelope);
        }
    }

    private void rememberPaymentIntentOrderReference(Map<String, Object> payload) {
        paymentIntentOrderReferences.save(text(payload, "paymentIntentId"), text(payload, "businessRef"));
    }

    private void recognizeCapturedPayment(EventEnvelope envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String paymentIntentId = text(payload, "paymentIntentId");
        String orderReference = paymentIntentOrderReferences.findOrderReference(paymentIntentId)
            .orElseThrow(() -> new OutOfOrderEventException("PaymentCaptured received before PaymentIntentCreated"));
        RevenueRecognition recognition = RevenueRecognition.recognize(
            orderReference,
            paymentIntentId,
            "fare",
            money(payload.get("capturedAmount"), "capturedAmount"),
            "payment-capture-v1",
            envelope.eventId(),
            envelope.occurredAt(),
            clock.instant(),
            causationIdOrEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(recognition);
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

    private static final class OutOfOrderEventException extends RuntimeException {
        private OutOfOrderEventException(String message) {
            super(message);
        }
    }
}
