package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.financesettlement.domain.FinanceSettlementEvent;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCaseOpened;
import com.trainticket.financesettlement.domain.ReconciliationCaseResolved;
import com.trainticket.financesettlement.domain.RevenueRecognized;
import com.trainticket.financesettlement.domain.SettlementViewRebuilt;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DomainEventEnvelopeMapper {
    public static final String PRODUCER = "finance-settlement";

    public EventEnvelope toEnvelope(FinanceSettlementEvent event) {
        return new EventEnvelope(
            canonicalEventId(event.eventId()),
            event.eventType(),
            event.occurredAt(),
            canonicalCorrelationId(event.correlationId()),
            canonicalCausationId(event.causationId()),
            PRODUCER,
            event.schemaVersion(),
            payload(event)
        );
    }

    private static Map<String, Object> payload(FinanceSettlementEvent event) {
        return switch (event) {
            case RevenueRecognized recognized -> revenueRecognizedPayload(recognized);
            case ReconciliationCaseOpened opened -> reconciliationCaseOpenedPayload(opened);
            case ReconciliationCaseResolved resolved -> reconciliationCaseResolvedPayload(resolved);
            case SettlementViewRebuilt rebuilt -> settlementViewRebuiltPayload(rebuilt);
        };
    }

    private static Map<String, Object> revenueRecognizedPayload(RevenueRecognized event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revenueRecognitionId", event.revenueRecognitionId());
        payload.put("orderItemId", event.orderItemId());
        payload.put("orderId", event.orderId());
        payload.put("componentCode", event.componentCode());
        payload.put("amount", moneyPayload(event.amount()));
        payload.put("recognitionPolicyVersion", event.recognitionPolicyVersion());
        payload.put("sourceEventId", event.sourceEventId());
        return payload;
    }

    private static Map<String, Object> reconciliationCaseOpenedPayload(ReconciliationCaseOpened event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reconciliationCaseId", event.reconciliationCaseId());
        payload.put("orderId", event.orderId());
        payload.put("paymentIntentId", event.paymentIntentId());
        payload.put("differenceType", event.differenceType());
        payload.put("expectedAmount", moneyPayload(event.expectedAmount()));
        payload.put("actualAmount", moneyPayload(event.actualAmount()));
        payload.put("description", event.description());
        return payload;
    }

    private static Map<String, Object> reconciliationCaseResolvedPayload(ReconciliationCaseResolved event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reconciliationCaseId", event.reconciliationCaseId());
        payload.put("resolution", event.resolution());
        payload.put("resolutionNote", event.resolutionNote());
        return payload;
    }

    private static Map<String, Object> settlementViewRebuiltPayload(SettlementViewRebuilt event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementViewId", event.settlementViewId());
        payload.put("viewType", event.viewType());
        payload.put("eventCount", event.eventCount());
        return payload;
    }

    public static Map<String, Object> moneyPayload(Money money) {
        return Map.of(
            "currency", money.currency().getCurrencyCode(),
            "minorUnits", money.amount().movePointRight(money.currency().getDefaultFractionDigits()).longValueExact()
        );
    }

    private static String canonicalEventId(String eventId) {
        String prefixed = eventId.startsWith("evt-") ? eventId : "evt-" + eventId;
        return PrefixedIds.isEventId(prefixed) ? prefixed : PrefixedIds.newEventId();
    }

    private static String canonicalCorrelationId(String correlationId) {
        String prefixed = correlationId.startsWith("corr-") ? correlationId : "corr-" + correlationId;
        return PrefixedIds.isCorrelationId(prefixed) ? prefixed : PrefixedIds.newCorrelationId();
    }

    private static String canonicalCausationId(String causationId) {
        String prefixed = (causationId.startsWith("cmd-") || causationId.startsWith("evt-")) ? causationId : "cmd-" + causationId;
        return PrefixedIds.isCausationId(prefixed) ? prefixed : PrefixedIds.newCommandId();
    }
}
