package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Objects;

public record SegmentOrderSnapshot(
    String segmentRef,
    String origin,
    String destination,
    String transportMode,
    Instant departureTime,
    Instant arrivalTime,
    String connectionContractRef
) {
    public SegmentOrderSnapshot {
        requireText(segmentRef, "segmentRef");
        requireText(origin, "origin");
        requireText(destination, "destination");
        requireText(transportMode, "transportMode");
        Objects.requireNonNull(departureTime, "departureTime is required");
        Objects.requireNonNull(arrivalTime, "arrivalTime is required");
        if (!arrivalTime.isAfter(departureTime)) {
            throw new DomainRuleViolation("segment arrivalTime must be after departureTime");
        }
        connectionContractRef = connectionContractRef == null ? "" : connectionContractRef;
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
    }
}
