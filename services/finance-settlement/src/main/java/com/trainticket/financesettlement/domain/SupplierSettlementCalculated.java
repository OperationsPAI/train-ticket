package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SupplierSettlementCalculated(
    String supplierSettlementId,
    String supplierId,
    SettlementPeriod period,
    Money grossRevenue,
    Money platformCommission,
    Money taxesWithheld,
    Money supplierPayable,
    Money adjustments,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public SupplierSettlementCalculated {
        requireText(supplierSettlementId, "supplierSettlementId");
        requireText(supplierId, "supplierId");
        Objects.requireNonNull(period, "period is required");
        Objects.requireNonNull(grossRevenue, "grossRevenue is required");
        Objects.requireNonNull(platformCommission, "platformCommission is required");
        Objects.requireNonNull(taxesWithheld, "taxesWithheld is required");
        Objects.requireNonNull(supplierPayable, "supplierPayable is required");
        Objects.requireNonNull(adjustments, "adjustments is required");
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
