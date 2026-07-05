package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentIntentCancelled(
    EventEnvelope envelope,
    String paymentIntentId,
    String reason
) implements PaymentEvent {}
