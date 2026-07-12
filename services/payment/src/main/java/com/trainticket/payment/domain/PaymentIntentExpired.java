package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentIntentExpired(
    EventEnvelope envelope,
    String paymentIntentId
) implements PaymentEvent {}
