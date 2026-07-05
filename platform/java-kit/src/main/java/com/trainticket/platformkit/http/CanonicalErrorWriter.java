package com.trainticket.platformkit.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;

public class CanonicalErrorWriter {
    private final ObjectMapper objectMapper;

    public CanonicalErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ApiErrorCode code, String message, String correlationId) throws IOException {
        write(response, code, message, correlationId, Map.of());
    }

    public void write(HttpServletResponse response, ApiErrorCode code, String message, String correlationId, Map<String, ?> details) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code.name(), message, correlationId, details));
    }
}
