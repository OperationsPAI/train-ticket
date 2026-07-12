package com.trainticket.platformkit.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, String correlationId, Map<String, ?> details) {
}
