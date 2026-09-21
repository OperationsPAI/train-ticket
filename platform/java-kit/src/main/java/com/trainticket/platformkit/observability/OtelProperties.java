package com.trainticket.platformkit.observability;

import java.time.Duration;
import java.util.Locale;
import org.springframework.core.env.Environment;

public final class OtelProperties {
    private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofSeconds(60);

    private OtelProperties() {
    }

    static boolean tracesEnabled(Environment environment) {
        return "otlp".equals(normalized(environment.getProperty("otel.traces.exporter")))
            && hasText(environment.getProperty("otel.exporter.otlp.endpoint"))
            && hasText(environment.getProperty("otel.service.name"));
    }

    static boolean metricsEnabled(Environment environment) {
        return "otlp".equals(normalized(environment.getProperty("otel.metrics.exporter")))
            && hasText(environment.getProperty("otel.exporter.otlp.endpoint"))
            && hasText(environment.getProperty("otel.service.name"));
    }

    static String serviceName(Environment environment) {
        return requireText(environment.getProperty("otel.service.name"), "otel.service.name is required");
    }

    static String otlpEndpoint(Environment environment) {
        return requireText(environment.getProperty("otel.exporter.otlp.endpoint"), "otel.exporter.otlp.endpoint is required");
    }

    /**
     * The periodic reader's interval, from the standard SDK variable.
     *
     * {@code OTEL_METRIC_EXPORT_INTERVAL} is specified in milliseconds, and the
     * spec's own default is 60 seconds. A value that is not a positive number of
     * milliseconds is rejected rather than replaced by the default: a typo there
     * would otherwise export on a period nobody chose.
     */
    static Duration metricExportInterval(Environment environment) {
        String configured = environment.getProperty("otel.metric.export.interval");
        if (!hasText(configured)) {
            return DEFAULT_METRIC_EXPORT_INTERVAL;
        }
        long millis = Long.parseLong(configured.trim());
        if (millis <= 0) {
            throw new IllegalStateException("otel.metric.export.interval must be a positive number of milliseconds");
        }
        return Duration.ofMillis(millis);
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
