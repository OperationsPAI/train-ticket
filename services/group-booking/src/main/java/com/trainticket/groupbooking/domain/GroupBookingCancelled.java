package com.trainticket.groupbooking.domain;

import java.time.Instant;
import java.util.List;

public record GroupBookingCancelled(
    String groupBookingId,
    String reason,
    List<String> cancelledMemberIds,
    Instant occurredAt
) implements GroupBookingEvent {
    @Override
    public String eventType() {
        return "GroupBookingCancelled";
    }
}
