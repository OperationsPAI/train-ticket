package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReconciliationCompleted(
    String reconciliationId,
    String orderId,
    String paymentIntentId,
    String reconciliationStatus,
    Money expectedAmount,
    Money actualAmount,
    List<String> matchedRevenueRecognitionIds,
    List<String> sourceEventIds,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public ReconciliationCompleted {
        requireText(reconciliationId, "reconciliationId");
        requireText(orderId, "orderId");
        requireText(reconciliationStatus, "reconciliationStatus");
        Objects.requireNonNull(expectedAmount, "expectedAmount is required");
        Objects.requireNonNull(actualAmount, "actualAmount is required");
        Objects.requireNonNull(metadata, "metadata is required");
        matchedRevenueRecognitionIds = List.copyOf(matchedRevenueRecognitionIds);
        sourceEventIds = List.copyOf(sourceEventIds);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }

    public String eventId() { return metadata.eventId(); }
    public Instant occurredAt() { return metadata.occurredAt(); }
    public String sourceCommandId() { return metadata.sourceCommandId(); }
    public String causationId() { return metadata.causationId(); }
    public String correlationId() { return metadata.correlationId(); }
    public int schemaVersion() { return metadata.schemaVersion(); }
    public Map<String, String> attributes() { return metadata.attributes(); }
}
