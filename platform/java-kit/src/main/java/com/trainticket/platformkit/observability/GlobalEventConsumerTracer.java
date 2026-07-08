package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.GlobalOpenTelemetry;

public final class GlobalEventConsumerTracer implements EventConsumerTracer {
    private final String instrumentationName;

    public GlobalEventConsumerTracer(String instrumentationName) {
        this.instrumentationName = instrumentationName;
    }

    @Override
    public SpanScope start(String stream, String consumerGroup, EventEnvelope envelope) {
        return new OtelEventConsumerTracer(GlobalOpenTelemetry.getTracer(instrumentationName))
            .start(stream, consumerGroup, envelope);
    }
}
