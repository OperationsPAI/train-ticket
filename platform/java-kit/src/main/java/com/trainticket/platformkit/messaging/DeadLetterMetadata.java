package com.trainticket.platformkit.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DeadLetterMetadata(
    String consumerGroup,
    String consumerName,
    String failureReason,
    int attempts,
    Instant deadLetteredAt
) {
    static final int MAX_FAILURE_REASON_LENGTH = 500;

    public DeadLetterMetadata {
        consumerGroup = Objects.requireNonNullElse(consumerGroup, "unknown");
        consumerName = Objects.requireNonNullElse(consumerName, "unknown");
        failureReason = truncate(Objects.requireNonNullElse(failureReason, "unknown"));
        attempts = Math.max(1, attempts);
        deadLetteredAt = Objects.requireNonNull(deadLetteredAt, "deadLetteredAt is required");
    }

    public static DeadLetterMetadata now(String consumerGroup, String consumerName, String failureReason, int attempts) {
        return now(consumerGroup, consumerName, failureReason, attempts, Clock.systemUTC());
    }

    static DeadLetterMetadata now(String consumerGroup, String consumerName, String failureReason, int attempts, Clock clock) {
        return new DeadLetterMetadata(consumerGroup, consumerName, failureReason, attempts, Instant.now(clock));
    }

    Map<String, String> toRedisFields(String envelopeJson) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("envelope", envelopeJson);
        fields.put("consumerGroup", consumerGroup);
        fields.put("consumerName", consumerName);
        fields.put("failureReason", failureReason);
        fields.put("attempts", String.valueOf(attempts));
        fields.put("deadLetteredAt", deadLetteredAt.toString());
        return fields;
    }

    static String truncate(String reason) {
        if (reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
