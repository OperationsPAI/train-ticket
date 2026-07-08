package com.trainticket.platformkit.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

public final class GlobalOpenTelemetryRegistrar implements SmartLifecycle {
    private final ObjectProvider<OpenTelemetry> openTelemetry;
    private boolean running;

    public GlobalOpenTelemetryRegistrar(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void start() {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        if (sdk == null) {
            running = true;
            return;
        }
        try {
            GlobalOpenTelemetry.set(sdk);
        } catch (IllegalStateException ignored) {
            // Another application context in the same JVM already registered OpenTelemetry.
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
