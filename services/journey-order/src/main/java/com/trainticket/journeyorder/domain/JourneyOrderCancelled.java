package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderCancelled(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String reason
) implements JourneyOrderEvent {}
