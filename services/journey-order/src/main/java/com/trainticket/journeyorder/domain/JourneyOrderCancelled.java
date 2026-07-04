package com.trainticket.journeyorder.domain;

public record JourneyOrderCancelled(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String reason
) implements JourneyOrderEvent {}