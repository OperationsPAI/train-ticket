package com.trainticket.payment;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
