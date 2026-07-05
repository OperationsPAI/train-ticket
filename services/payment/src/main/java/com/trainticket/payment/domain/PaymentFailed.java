package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record PaymentFailed(
    EventEnvelope envelope,
    String paymentIntentId,
    String reasonCode,
    boolean retryable
) implements PaymentEvent {}
