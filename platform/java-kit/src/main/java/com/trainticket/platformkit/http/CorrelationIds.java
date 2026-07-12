package com.trainticket.platformkit.http;

import com.trainticket.platformkit.idempotency.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

public final class CorrelationIds {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String CORRELATION_ATTRIBUTE = CorrelationIds.class.getName() + ".correlationId";

    private CorrelationIds() {
    }

    public static String from(HttpServletRequest request) {
        Object attribute = request.getAttribute(CORRELATION_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        return Optional.ofNullable(request.getHeader(CORRELATION_HEADER))
            .filter(UuidV7::isValid)
            .orElseGet(UuidV7::generate);
    }
}
