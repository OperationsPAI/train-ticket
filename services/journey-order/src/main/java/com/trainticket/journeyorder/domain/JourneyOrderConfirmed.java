package com.trainticket.journeyorder.domain;

public record JourneyOrderConfirmed(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    MonetarySummary monetarySummary,
    java.time.Instant confirmedAt
) implements JourneyOrderEvent {}