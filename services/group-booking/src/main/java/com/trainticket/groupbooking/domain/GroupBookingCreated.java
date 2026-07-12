package com.trainticket.groupbooking.domain;

import java.time.Instant;
import java.util.List;

public record GroupBookingCreated(
    String groupBookingId,
    String organizerRef,
    List<String> segmentRefs,
    int targetTravelerCount,
    GroupFare fare,
    Instant occurredAt
) implements GroupBookingEvent {
    @Override
    public String eventType() {
        return "GroupBookingCreated";
    }
}
