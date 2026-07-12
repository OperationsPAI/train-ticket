package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Instant;

public record PaymentTimedOut(
    EventEnvelope envelope,
    String paymentIntentId,
    String businessRef,
    Instant expiresAt,
    String reason
) implements PaymentEvent {
}
