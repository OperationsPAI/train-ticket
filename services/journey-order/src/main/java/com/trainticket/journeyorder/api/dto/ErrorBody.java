package com.trainticket.journeyorder.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ErrorBody(
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("correlationId") String correlationId,
    @JsonProperty("details") Map<String, Object> details
) {
    public static ErrorBody of(String code, String message, String correlationId) {
        return new ErrorBody(code, message, correlationId, Map.of());
    }

    public static ErrorBody of(String code, String message, String correlationId, Map<String, Object> details) {
        return new ErrorBody(code, message, correlationId, details);
    }
}
