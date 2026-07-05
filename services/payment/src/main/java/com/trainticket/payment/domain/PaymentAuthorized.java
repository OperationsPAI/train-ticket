package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentAuthorized(
    EventEnvelope envelope,
    String paymentIntentId,
    Money authorizedAmount,
    String channel,
    String channelTransactionId
) implements PaymentEvent {}
