package com.trainticket.adminaudit;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
