package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.time.Instant;
import java.util.Objects;

public record AncillaryFinancialFact(
    String eventId,
    String eventType,
    String factKind,
    String ancillaryOrderItemId,
    String journeyOrderId,
    String serviceType,
    String supplierRef,
    Money payableAmount,
    Money refundableAmount,
    Money refundedAmount,
    Money retainedAmount,
    Instant occurredAt
) {
    public AncillaryFinancialFact {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        factKind = requireText(factKind, "factKind");
        ancillaryOrderItemId = requireText(ancillaryOrderItemId, "ancillaryOrderItemId");
        journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        serviceType = requireText(serviceType, "serviceType");
        supplierRef = supplierRef == null || supplierRef.isBlank() ? null : supplierRef;
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
