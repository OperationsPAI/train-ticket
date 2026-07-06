package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record InvoiceGenerated(
    String invoiceId,
    String orderId,
    String invoiceNumber,
    Money totalAmount,
    List<String> revenueRecognitionIds,
    Instant generatedAt,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public InvoiceGenerated {
        requireText(invoiceId, "invoiceId");
        requireText(orderId, "orderId");
        requireText(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(totalAmount, "totalAmount is required");
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        Objects.requireNonNull(metadata, "metadata is required");
        revenueRecognitionIds = List.copyOf(revenueRecognitionIds);
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
