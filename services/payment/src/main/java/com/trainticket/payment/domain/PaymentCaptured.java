package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentCaptured(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    Money capturedAmount,
    String channel,
    String channelTransactionId
) implements PaymentEvent {}
