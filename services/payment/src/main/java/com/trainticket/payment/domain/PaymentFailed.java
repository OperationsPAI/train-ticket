package com.trainticket.payment.domain;

public record PaymentFailed(
    String paymentIntentId,
    String reasonCode,
    boolean retryable,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentFailed";
    }
}
