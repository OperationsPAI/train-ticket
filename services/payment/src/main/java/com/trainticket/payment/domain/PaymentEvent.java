package com.trainticket.payment.domain;

public sealed interface PaymentEvent permits
    PaymentIntentCreated,
    PaymentAuthorized,
    PaymentCaptured,
    PaymentFailed,
    PaymentIntentCancelled,
    PaymentIntentExpired,
    RefundRequested,
    RefundSettled,
    RefundFailed,
    ChannelCallbackReceived,
    DuplicateChannelCallbackDetected,
    LatePaymentDetected {
    EventEnvelope envelope();
}
