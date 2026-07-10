package com.trainticket.groupbooking.domain;

import java.time.Instant;

public sealed interface GroupBookingEvent permits GroupBookingCreated, GroupMemberAdded, GroupBookingConfirmed, GroupBookingCancelled {
    String eventType();
    String groupBookingId();
    Instant occurredAt();
}
