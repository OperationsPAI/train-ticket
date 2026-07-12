package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ConsumedEventLog {
    private final String logId;
    private final String eventId;
    private final String source;
    private final String eventType;
    private final Instant consumedAt;

    private ConsumedEventLog(String logId, String eventId, String source, String eventType, Instant consumedAt) {
        this.logId = requireText(logId, "logId");
        this.eventId = requireText(eventId, "eventId");
        this.source = requireText(source, "source");
        this.eventType = requireText(eventType, "eventType");
        this.consumedAt = Objects.requireNonNull(consumedAt, "consumedAt is required");
    }

    public static ConsumedEventLog record(String eventId, String source, String eventType, Instant consumedAt) {
        return new ConsumedEventLog(
            UUID.randomUUID().toString(),
            requireText(eventId, "eventId"),
            requireText(source, "source"),
            requireText(eventType, "eventType"),
            Objects.requireNonNull(consumedAt, "consumedAt is required")
        );
    }

    public String logId() { return logId; }
    public String eventId() { return eventId; }
    public String source() { return source; }
    public String eventType() { return eventType; }
    public Instant consumedAt() { return consumedAt; }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
