package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record DuplicateChannelCallbackDetected(
    EventEnvelope envelope,
    String callbackRecordId,
    String channel,
    String callbackId,
    String firstCallbackRecordId
) implements PaymentEvent {}
