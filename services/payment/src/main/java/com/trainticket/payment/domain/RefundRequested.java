package com.trainticket.payment.domain;

public record RefundRequested(
    String refundId,
    String paymentIntentId,
    Money amount,
    String sourceCaseRef,
    String reasonCode,
    String idempotencyKey,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "RefundRequested";
    }
}
