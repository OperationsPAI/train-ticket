package com.trainticket.financesettlement.application;

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
    private final Clock clock;
    private final FinanceSettlementApplicationService service;

    public FinanceSettlementEventHandler(ConsumedEventLogRepository consumedEvents, Clock clock) {
        this(consumedEvents, clock, null);
    }

    public FinanceSettlementEventHandler(ConsumedEventLogRepository consumedEvents, Clock clock, FinanceSettlementApplicationService service) {
        this.consumedEvents = consumedEvents;
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
        } catch (PublishFailedException ex) {
            return HandlerResult.TRANSIENT_FAILURE;
        } catch (DomainRuleViolation | IllegalArgumentException ex) {
            return HandlerResult.FATAL_FAILURE;
        }
    }

    private void dispatch(EventEnvelope envelope) {
        if (service == null) {
            return;
        }
        if ("PaymentCaptured".equals(envelope.eventType())) {
            recognizeCapturedPayment(envelope);
        }
    }

    private void recognizeCapturedPayment(EventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        RevenueRecognition recognition = RevenueRecognition.recognize(
            text(payload, "orderId"),
            optionalText(payload, "orderItemId", optionalText(payload, "paymentIntentId", envelope.eventId())),
            optionalText(payload, "componentCode", "fare"),
            money(payload.get("capturedAmount"), "capturedAmount"),
            optionalText(payload, "recognitionPolicyVersion", "payment-capture-v1"),
            envelope.eventId(),
            envelope.occurredAt(),
            clock.instant(),
            causationIdOrSourceEventId(envelope),
            envelope.correlationId()
        );
        service.saveAndPublish(recognition);
    }

    private static String causationIdOrSourceEventId(EventEnvelope envelope) {
        return envelope.causationId() == null ? envelope.eventId() : envelope.causationId();
    }

    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> payload, String name, String defaultValue) {
        Object value = payload.get(name);
        return value instanceof String text && !text.isBlank() ? text : defaultValue;
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
}
