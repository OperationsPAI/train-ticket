package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;

public final class GlobalEventConsumerTracer implements EventConsumerTracer {
    private final String instrumentationName;

    public GlobalEventConsumerTracer(String instrumentationName) {
        this.instrumentationName = instrumentationName;
    }

    @Override
    public SpanScope start(String stream, String consumerGroup, EventEnvelope envelope) {
        var openTelemetry = ProcessOpenTelemetry.get();
        return new OtelEventConsumerTracer(openTelemetry, openTelemetry.getTracer(instrumentationName))
            .start(stream, consumerGroup, envelope);
    }
}
