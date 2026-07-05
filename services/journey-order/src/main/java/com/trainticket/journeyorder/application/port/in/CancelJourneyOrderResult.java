package com.trainticket.journeyorder.application.port.in;

import java.time.Instant;

public record CancelJourneyOrderResult(
    String orderId,
    String status,
    Instant cancelledAt
) {}
