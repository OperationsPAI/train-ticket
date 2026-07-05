package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
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
