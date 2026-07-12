package com.trainticket.bookingorchestration;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
