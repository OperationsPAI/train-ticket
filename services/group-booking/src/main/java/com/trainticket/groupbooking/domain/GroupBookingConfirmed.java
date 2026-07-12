package com.trainticket.groupbooking.domain;

import java.time.Instant;
import java.util.List;

public record GroupBookingConfirmed(
    String groupBookingId,
    String capacityHoldId,
    int confirmedTravelerCount,
    List<String> memberIds,
    Instant occurredAt
) implements GroupBookingEvent {
    @Override
    public String eventType() {
        return "GroupBookingConfirmed";
    }
}
