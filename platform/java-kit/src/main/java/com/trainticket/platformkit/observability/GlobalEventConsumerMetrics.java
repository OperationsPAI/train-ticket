package com.trainticket.platformkit.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Resolves the process-global meter lazily.
 *
 * <p>Subscribers are constructed while Spring is still creating beans, before the SDK is
 * registered globally. Resolving once in the constructor would permanently bind them to the
 * no-op provider. The identity check changes the delegate exactly when the registrar installs the
 * real provider; steady-state recording is a volatile read.
 */
public final class GlobalEventConsumerMetrics implements EventConsumerMetrics {
    private final String instrumentationName;
    private volatile OpenTelemetry boundOpenTelemetry;
    private volatile EventConsumerMetrics delegate;

    public GlobalEventConsumerMetrics(String instrumentationName) {
        this.instrumentationName = instrumentationName;
    }

    @Override
    public void recordOutcome(Outcome outcome) {
        delegate().recordOutcome(outcome);
    }

    @Override
    public void recordFailure(FailureType failureType) {
        delegate().recordFailure(failureType);
    }

    @Override
    public void recordDeadLetter(FailureType reason) {
        delegate().recordDeadLetter(reason);
    }

    private EventConsumerMetrics delegate() {
        OpenTelemetry current = GlobalOpenTelemetry.get();
        EventConsumerMetrics currentDelegate = delegate;
        if (currentDelegate != null && current == boundOpenTelemetry) {
            return currentDelegate;
        }
        synchronized (this) {
            if (delegate == null || current != boundOpenTelemetry) {
                delegate = new OtelEventConsumerMetrics(current.getMeter(instrumentationName));
                boundOpenTelemetry = current;
            }
            return delegate;
        }
    }
}
