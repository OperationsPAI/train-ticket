package com.trainticket.platformkit.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorBody(String code, String message, String correlationId, Map<String, Object> details) {
    public ErrorBody { details = details == null ? Map.of() : Map.copyOf(details); }
    public static ErrorBody of(String code, String message, String correlationId) { return new ErrorBody(code, message, correlationId, Map.of()); }
    public static ErrorBody of(String code, String message, String correlationId, Map<String, Object> details) { return new ErrorBody(code, message, correlationId, details); }
}
