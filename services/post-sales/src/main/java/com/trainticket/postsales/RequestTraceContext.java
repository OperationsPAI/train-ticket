package com.trainticket.postsales;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
