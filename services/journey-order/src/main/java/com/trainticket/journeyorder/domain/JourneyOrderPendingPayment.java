package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record JourneyOrderPendingPayment(
    EventEnvelope envelope,
    String orderId,
    String accountId,
    String paymentPurpose,
    MonetarySummary monetarySummary
) implements JourneyOrderEvent {}
