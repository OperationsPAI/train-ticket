package com.trainticket.journeyorder.application.port.in;

import java.util.List;

public record OrderListResult(
    List<JourneyOrderResult> items,
    int total,
    int limit,
    int offset
) {}
