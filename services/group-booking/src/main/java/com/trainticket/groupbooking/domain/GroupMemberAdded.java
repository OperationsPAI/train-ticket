package com.trainticket.groupbooking.domain;

import java.time.Instant;

public record GroupMemberAdded(
    String groupBookingId,
    String memberId,
    String travelerRef,
    Instant occurredAt
) implements GroupBookingEvent {
    @Override
    public String eventType() {
        return "GroupMemberAdded";
    }
}
