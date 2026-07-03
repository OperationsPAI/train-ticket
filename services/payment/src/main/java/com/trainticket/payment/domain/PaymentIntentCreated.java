package com.trainticket.payment.domain;

public record PaymentIntentCreated(
    String paymentIntentId,
    String businessRef,
    String purpose,
    Money amount,
    String payerRef,
    String idempotencyKey,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentIntentCreated";
    }
}
