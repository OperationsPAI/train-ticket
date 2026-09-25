package com.trainticket.platformkit.observability;

import io.opentelemetry.api.OpenTelemetry;
import java.util.Objects;

/**
 * Process-local OpenTelemetry handle for components created before Spring lifecycle start.
 *
 * <p>{@code GlobalOpenTelemetry.get()} is not a harmless read before registration: the API installs
 * its no-op singleton on the first call, after which {@code GlobalOpenTelemetry.set(...)} is
 * rejected. Background components such as the outbox relay can run immediately after bean
 * creation, so their first metric must not make that global decision for the application.
 */
final class ProcessOpenTelemetry {
    private static volatile OpenTelemetry current = OpenTelemetry.noop();

    private ProcessOpenTelemetry() {
    }

    static OpenTelemetry get() {
        return current;
    }

    static void register(OpenTelemetry openTelemetry) {
        current = Objects.requireNonNull(openTelemetry, "openTelemetry is required");
    }

    static void resetForTest() {
        current = OpenTelemetry.noop();
    }
}
