package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.time.Instant;
import java.util.Objects;

public record BenefitCostEntry(
    String eventId,
    String benefitId,
    String accountId,
    String issuanceSource,
    String caseId,
    Money amount,
    String eventType,
    Instant occurredAt
) {
    public BenefitCostEntry {
        eventId = requireText(eventId, "eventId");
        benefitId = requireText(benefitId, "benefitId");
        accountId = requireText(accountId, "accountId");
        issuanceSource = requireText(issuanceSource, "issuanceSource");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        caseId = caseId == null || caseId.isBlank() ? null : caseId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
