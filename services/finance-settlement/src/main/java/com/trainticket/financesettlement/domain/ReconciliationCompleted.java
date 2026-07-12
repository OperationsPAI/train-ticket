package com.trainticket.financesettlement.domain;

import java.math.BigDecimal;
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
    String batchId,
    String settlementDate,
    int totalEntries,
    int matchedEntries,
    BigDecimal matchRate,
    Money totalVariance,
    int exceptionCount,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public ReconciliationCompleted(
        String reconciliationId,
        String orderId,
        String paymentIntentId,
        String reconciliationStatus,
        Money expectedAmount,
        Money actualAmount,
        List<String> matchedRevenueRecognitionIds,
        List<String> sourceEventIds,
        EventMetadata metadata
    ) {
        this(reconciliationId, orderId, paymentIntentId, reconciliationStatus, expectedAmount, actualAmount,
            matchedRevenueRecognitionIds, sourceEventIds, "", "", 0, 0, BigDecimal.ZERO.setScale(4), Money.zero(expectedAmount.currency()), 0, metadata);
    }

    public ReconciliationCompleted {
        requireText(reconciliationId, "reconciliationId");
        orderId = orderId == null ? "" : orderId;
        paymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
        requireText(reconciliationStatus, "reconciliationStatus");
        Objects.requireNonNull(expectedAmount, "expectedAmount is required");
        Objects.requireNonNull(actualAmount, "actualAmount is required");
        matchedRevenueRecognitionIds = List.copyOf(matchedRevenueRecognitionIds == null ? List.of() : matchedRevenueRecognitionIds);
        sourceEventIds = List.copyOf(sourceEventIds == null ? List.of() : sourceEventIds);
        batchId = batchId == null ? "" : batchId;
        settlementDate = settlementDate == null ? "" : settlementDate;
        matchRate = Objects.requireNonNull(matchRate, "matchRate is required");
        totalVariance = Objects.requireNonNull(totalVariance, "totalVariance is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public static ReconciliationCompleted forBatch(
        String batchId,
        String settlementDate,
        int totalEntries,
        int matchedEntries,
        BigDecimal matchRate,
        Money totalVariance,
        int exceptionCount,
        EventMetadata metadata
    ) {
        return new ReconciliationCompleted(
            batchId,
            "",
            "",
            "COMPLETED",
            Money.zero(totalVariance.currency()),
            Money.zero(totalVariance.currency()),
            List.of(),
            List.of(),
            batchId,
            settlementDate,
            totalEntries,
            matchedEntries,
            matchRate,
            totalVariance,
            exceptionCount,
            metadata
        );
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
