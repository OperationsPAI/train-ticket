package com.trainticket.financesettlement;

public record RequestTraceContext(
    String requestId,
    String correlationId,
    String method,
    String path
) {
}
