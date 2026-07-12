package com.trainticket.financesettlement.domain;

import java.util.List;
import java.util.Objects;

public record ReconciliationEntry(
    String entryId,
    String orderId,
    String paymentIntentId,
    Money platformAmount,
    Money channelAmount,
    ReconciliationStatus status,
    Money variance,
    List<String> sourceEventIds
) {
    public ReconciliationEntry {
        requireText(entryId, "entryId");
        orderId = orderId == null ? "" : orderId;
        paymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
        Objects.requireNonNull(platformAmount, "platformAmount is required");
        Objects.requireNonNull(channelAmount, "channelAmount is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(variance, "variance is required");
        sourceEventIds = List.copyOf(sourceEventIds == null ? List.of() : sourceEventIds);
    }

    public static ReconciliationEntry compare(
        String entryId,
        String orderId,
        String paymentIntentId,
        Money platformAmount,
        Money channelAmount,
        boolean platformPresent,
        boolean channelPresent,
        List<String> sourceEventIds
    ) {
        ReconciliationStatus status;
        if (platformPresent && channelPresent) {
            status = platformAmount.compareTo(channelAmount) == 0 ? ReconciliationStatus.MATCHED : ReconciliationStatus.AMOUNT_MISMATCH;
        } else if (platformPresent) {
            status = ReconciliationStatus.PLATFORM_ONLY;
        } else {
            status = ReconciliationStatus.CHANNEL_ONLY;
        }
        return new ReconciliationEntry(entryId, orderId, paymentIntentId, platformAmount, channelAmount, status, platformAmount.minus(channelAmount), sourceEventIds);
    }

    public boolean isException() {
        return status != ReconciliationStatus.MATCHED;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
