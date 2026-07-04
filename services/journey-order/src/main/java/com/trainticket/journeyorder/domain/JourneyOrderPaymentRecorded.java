package com.trainticket.journeyorder.domain;

public record JourneyOrderPaymentRecorded(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String paymentIntentId
) implements JourneyOrderEvent {}