package com.trainticket.journeyorder.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public sealed interface JourneyOrderEvent permits
    JourneyOrderCreated,
    JourneyOrderPendingPayment,
    JourneyOrderConfirmed,
    JourneyOrderCancelled,
    JourneyOrderPostSalesAdjusted,
    JourneyOrderPaymentRecorded {
    EventEnvelope envelope();
    String orderId();
    String accountId();
}
