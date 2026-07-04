package com.trainticket.payment.domain;

public record PaymentAuthorized(
    EventEnvelope envelope,
    String paymentIntentId,
    Money authorizedAmount,
    String channel,
    String channelTransactionId
) implements PaymentEvent {}