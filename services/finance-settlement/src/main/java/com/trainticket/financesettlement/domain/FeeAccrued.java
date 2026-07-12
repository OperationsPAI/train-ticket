package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record FeeAccrued(
    String feeAccrualId,
    String orderId,
    Money platformServiceFee,
    Money supplierServiceFee,
    Money retainedCancellationFee,
    TaxCalculation taxCalculation,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public FeeAccrued {
        requireText(feeAccrualId, "feeAccrualId");
        requireText(orderId, "orderId");
        Objects.requireNonNull(platformServiceFee, "platformServiceFee is required");
        Objects.requireNonNull(supplierServiceFee, "supplierServiceFee is required");
        Objects.requireNonNull(retainedCancellationFee, "retainedCancellationFee is required");
        Objects.requireNonNull(taxCalculation, "taxCalculation is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) throw new DomainRuleViolation(name + " must not be blank");
    }

    public String eventId() { return metadata.eventId(); }
    public Instant occurredAt() { return metadata.occurredAt(); }
    public String sourceCommandId() { return metadata.sourceCommandId(); }
    public String causationId() { return metadata.causationId(); }
    public String correlationId() { return metadata.correlationId(); }
    public int schemaVersion() { return metadata.schemaVersion(); }
    public Map<String, String> attributes() { return metadata.attributes(); }
}
