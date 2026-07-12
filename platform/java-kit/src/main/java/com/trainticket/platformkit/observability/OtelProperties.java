package com.trainticket.platformkit.observability;

import java.util.Locale;
import org.springframework.core.env.Environment;

public final class OtelProperties {
    private OtelProperties() {
    }

    static boolean tracesEnabled(Environment environment) {
        return "otlp".equals(normalized(environment.getProperty("otel.traces.exporter")))
            && hasText(environment.getProperty("otel.exporter.otlp.endpoint"))
            && hasText(environment.getProperty("otel.service.name"));
    }

    static String serviceName(Environment environment) {
        return requireText(environment.getProperty("otel.service.name"), "otel.service.name is required");
    }

    static String otlpEndpoint(Environment environment) {
        return requireText(environment.getProperty("otel.exporter.otlp.endpoint"), "otel.exporter.otlp.endpoint is required");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }
}
