package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Objects;

public record RecognizeRevenue(
    String orderId, String orderItemId, String componentCode,
    Money amount, String recognitionPolicyVersion,
    String sourceEventId, Instant recognizedAt
) {
    public RecognizeRevenue {
        requireText(orderId, "orderId");
        requireText(orderItemId, "orderItemId");
        requireText(componentCode, "componentCode");
        Objects.requireNonNull(amount, "amount is required");
        requireText(recognitionPolicyVersion, "recognitionPolicyVersion");
        requireText(sourceEventId, "sourceEventId");
        Objects.requireNonNull(recognizedAt, "recognizedAt is required");
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
    }
}
