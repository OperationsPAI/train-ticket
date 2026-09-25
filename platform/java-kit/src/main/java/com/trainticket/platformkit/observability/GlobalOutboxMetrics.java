package com.trainticket.platformkit.observability;

import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;

/** Lazily follows the globally registered SDK for relays created during Spring startup. */
public final class GlobalOutboxMetrics implements OutboxMetrics {
    private final String instrumentationName;
    private volatile OpenTelemetry boundOpenTelemetry;
    private volatile OutboxMetrics delegate;

    public GlobalOutboxMetrics(String instrumentationName) {
        this.instrumentationName = instrumentationName;
    }

    @Override
    public void observeUnpublishedSample(int rows, Duration oldestAge) {
        delegate().observeUnpublishedSample(rows, oldestAge);
    }

    @Override
    public void recordPublished(int rows) {
        delegate().recordPublished(rows);
    }

    @Override
    public void recordFailure(FailureStage stage) {
        delegate().recordFailure(stage);
    }

    private OutboxMetrics delegate() {
        OpenTelemetry current = ProcessOpenTelemetry.get();
        OutboxMetrics currentDelegate = delegate;
        if (currentDelegate != null && current == boundOpenTelemetry) {
            return currentDelegate;
        }
        synchronized (this) {
            if (delegate == null || current != boundOpenTelemetry) {
                delegate = new OtelOutboxMetrics(current.getMeter(instrumentationName));
                boundOpenTelemetry = current;
            }
            return delegate;
        }
    }
}
