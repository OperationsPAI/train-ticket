package com.trainticket.journeyorder;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
