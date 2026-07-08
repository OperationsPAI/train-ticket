package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public final class NoOpEventConsumerTracer implements EventConsumerTracer {
    public static final NoOpEventConsumerTracer INSTANCE = new NoOpEventConsumerTracer();

    private NoOpEventConsumerTracer() {
    }

    @Override
    public SpanScope start(String stream, String consumerGroup, EventEnvelope envelope) {
        return new SpanScope(Span.getInvalid(), Scope.noop());
    }
}
