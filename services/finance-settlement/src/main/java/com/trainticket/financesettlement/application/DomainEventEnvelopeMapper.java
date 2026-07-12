package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.financesettlement.domain.FeeAccrued;
import com.trainticket.financesettlement.domain.FinanceSettlementEvent;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.InvoiceGenerated;
import com.trainticket.financesettlement.domain.ReconciliationCaseOpened;
import com.trainticket.financesettlement.domain.ReconciliationCaseResolved;
import com.trainticket.financesettlement.domain.ReconciliationCompleted;
import com.trainticket.financesettlement.domain.RevenueRecognized;
import com.trainticket.financesettlement.domain.RevenueRecognitionReversed;
import com.trainticket.financesettlement.domain.SettlementViewRebuilt;
import com.trainticket.financesettlement.domain.SupplierSettlementCalculated;
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
            case RevenueRecognitionReversed reversed -> revenueRecognitionReversedPayload(reversed);
            case ReconciliationCaseOpened opened -> reconciliationCaseOpenedPayload(opened);
            case ReconciliationCaseResolved resolved -> reconciliationCaseResolvedPayload(resolved);
            case ReconciliationCompleted completed -> reconciliationCompletedPayload(completed);
            case SupplierSettlementCalculated calculated -> supplierSettlementCalculatedPayload(calculated);
            case FeeAccrued accrued -> feeAccruedPayload(accrued);
            case InvoiceGenerated generated -> invoiceGeneratedPayload(generated);
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
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> revenueRecognitionReversedPayload(RevenueRecognitionReversed event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revenueRecognitionId", event.revenueRecognitionId());
        payload.put("orderItemId", event.orderItemId());
        payload.put("orderId", event.orderId());
        payload.put("componentCode", event.componentCode());
        payload.put("amount", moneyPayload(event.amount()));
        payload.put("reversalReason", event.reversalReason());
        payload.put("sourceEventId", event.sourceEventId());
        payload.put("metadata", metadataPayload(event));
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
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> reconciliationCaseResolvedPayload(ReconciliationCaseResolved event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reconciliationCaseId", event.reconciliationCaseId());
        payload.put("resolution", event.resolution());
        payload.put("resolutionNote", event.resolutionNote());
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> reconciliationCompletedPayload(ReconciliationCompleted event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reconciliationId", event.reconciliationId());
        payload.put("orderId", event.orderId());
        payload.put("paymentIntentId", event.paymentIntentId());
        payload.put("reconciliationStatus", event.reconciliationStatus());
        payload.put("expectedAmount", moneyPayload(event.expectedAmount()));
        payload.put("actualAmount", moneyPayload(event.actualAmount()));
        payload.put("matchedRevenueRecognitionIds", event.matchedRevenueRecognitionIds());
        payload.put("sourceEventIds", event.sourceEventIds());
        if (!event.batchId().isBlank()) {
            payload.put("batchId", event.batchId());
            payload.put("settlementDate", event.settlementDate());
            payload.put("totalEntries", event.totalEntries());
            payload.put("matchedEntries", event.matchedEntries());
            payload.put("matchRate", event.matchRate());
            payload.put("totalVariance", moneyPayload(event.totalVariance()));
            payload.put("exceptionCount", event.exceptionCount());
        }
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> supplierSettlementCalculatedPayload(SupplierSettlementCalculated event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supplierSettlementId", event.supplierSettlementId());
        payload.put("supplierId", event.supplierId());
        payload.put("periodStartDate", event.period().startDate().toString());
        payload.put("periodEndDate", event.period().endDate().toString());
        payload.put("settlementFrequency", event.period().frequency().name());
        payload.put("grossRevenue", moneyPayload(event.grossRevenue()));
        payload.put("platformCommission", moneyPayload(event.platformCommission()));
        payload.put("taxesWithheld", moneyPayload(event.taxesWithheld()));
        payload.put("supplierPayable", moneyPayload(event.supplierPayable()));
        payload.put("adjustments", moneyPayload(event.adjustments()));
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> feeAccruedPayload(FeeAccrued event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feeAccrualId", event.feeAccrualId());
        payload.put("orderId", event.orderId());
        payload.put("platformServiceFee", moneyPayload(event.platformServiceFee()));
        payload.put("supplierServiceFee", moneyPayload(event.supplierServiceFee()));
        payload.put("retainedCancellationFee", moneyPayload(event.retainedCancellationFee()));
        payload.put("vatOnServiceFees", moneyPayload(event.taxCalculation().vatOnServiceFees()));
        payload.put("stampDutyOnTickets", moneyPayload(event.taxCalculation().stampDutyOnTickets()));
        payload.put("withholdingOnSupplierPayment", moneyPayload(event.taxCalculation().withholdingOnSupplierPayment()));
        payload.put("taxReversal", moneyPayload(event.taxCalculation().taxReversal()));
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> invoiceGeneratedPayload(InvoiceGenerated event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", event.invoiceId());
        payload.put("orderId", event.orderId());
        payload.put("invoiceNumber", event.invoiceNumber());
        payload.put("totalAmount", moneyPayload(event.totalAmount()));
        payload.put("revenueRecognitionIds", event.revenueRecognitionIds());
        payload.put("generatedAt", event.generatedAt().toString());
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> settlementViewRebuiltPayload(SettlementViewRebuilt event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementViewId", event.settlementViewId());
        payload.put("viewType", event.viewType());
        payload.put("eventCount", event.eventCount());
        payload.put("metadata", metadataPayload(event));
        return payload;
    }

    private static Map<String, Object> metadataPayload(FinanceSettlementEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", event.eventId());
        metadata.put("occurredAt", event.occurredAt().toString());
        metadata.put("sourceCommandId", event.sourceCommandId());
        metadata.put("causationId", event.causationId());
        metadata.put("correlationId", event.correlationId());
        metadata.put("schemaVersion", event.schemaVersion());
        metadata.put("attributes", event.attributes());
        return metadata;
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
        if (causationId == null || causationId.isBlank()) {
            return PrefixedIds.newCommandId();
        }
        String prefixed = (causationId.startsWith("cmd-") || causationId.startsWith("evt-")) ? causationId : "cmd-" + causationId;
        return PrefixedIds.isCausationId(prefixed) ? prefixed : PrefixedIds.newCommandId();
    }
}
