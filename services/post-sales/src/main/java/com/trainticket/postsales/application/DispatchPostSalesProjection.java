package com.trainticket.postsales.application;

import java.time.Instant;

public record DispatchPostSalesProjection(
    String rideRequestId,
    String rideAssignmentId,
    String riderAccountId,
    String travelerRef,
    String pickupRef,
    String dropoffRef,
    String driverRef,
    String vehicleRef,
    String intentFingerprint,
    String finalFareRef,
    String reason,
    String status,
    String previousStatus,
    Instant startedAt,
    Instant endedAt,
    Instant cancelledAt,
    Instant recordedAt,
    Instant failedAt,
    String lastEventId,
    String lastEventType,
    Instant lastOccurredAt
) {
    public DispatchPostSalesProjection {
        rideRequestId = requireText(rideRequestId, "rideRequestId");
        riderAccountId = requireText(riderAccountId, "riderAccountId");
        travelerRef = requireText(travelerRef, "travelerRef");
        pickupRef = requireText(pickupRef, "pickupRef");
        dropoffRef = requireText(dropoffRef, "dropoffRef");
        status = requireText(status, "status");
        lastEventId = requireText(lastEventId, "lastEventId");
        lastEventType = requireText(lastEventType, "lastEventType");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
