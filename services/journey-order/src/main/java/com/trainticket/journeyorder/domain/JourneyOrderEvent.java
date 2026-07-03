package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Map;

public sealed interface JourneyOrderEvent permits
    JourneyOrderCreated,
    JourneyOrderPendingPayment,
    JourneyOrderConfirmed,
    JourneyOrderCancelled,
    JourneyOrderPostSalesAdjusted,
    JourneyOrderPaymentRecorded {
    String eventId();
    Instant occurredAt();
    String orderId();
    String accountId();
    String sourceCommandId();
    String causationId();
    String correlationId();
    int schemaVersion();
    Map<String, String> attributes();
    default String eventType() {
        return getClass().getSimpleName();
    }
}
