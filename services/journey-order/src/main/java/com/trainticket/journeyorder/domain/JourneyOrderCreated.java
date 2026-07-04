package com.trainticket.journeyorder.domain;

public record JourneyOrderCreated(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String offerId,
    MonetarySummary monetarySummary
) implements JourneyOrderEvent {}