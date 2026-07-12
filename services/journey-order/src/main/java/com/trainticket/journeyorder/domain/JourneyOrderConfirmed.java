package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderConfirmed(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    MonetarySummary monetarySummary,
    java.time.Instant confirmedAt
) implements JourneyOrderEvent {}
