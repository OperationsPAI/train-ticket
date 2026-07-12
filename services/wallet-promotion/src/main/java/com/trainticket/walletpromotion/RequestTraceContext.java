package com.trainticket.walletpromotion;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
