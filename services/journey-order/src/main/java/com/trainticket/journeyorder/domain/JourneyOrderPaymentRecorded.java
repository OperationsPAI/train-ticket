package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderPaymentRecorded(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String paymentIntentId
) implements JourneyOrderEvent {}
