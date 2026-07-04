package com.trainticket.travelerprofile;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
