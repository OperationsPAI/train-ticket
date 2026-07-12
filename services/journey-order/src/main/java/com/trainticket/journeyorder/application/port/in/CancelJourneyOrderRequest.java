package com.trainticket.journeyorder.application.port.in;

public record CancelJourneyOrderRequest(
    String orderId,
    String reason
) {}
