package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public final class OtelEventConsumerTracer implements EventConsumerTracer {
    private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> MESSAGING_OPERATION = AttributeKey.stringKey("messaging.operation");
    private static final AttributeKey<String> MESSAGING_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> STREAM = AttributeKey.stringKey("messaging.stream");
    private static final AttributeKey<String> CONSUMER_GROUP = AttributeKey.stringKey("messaging.consumer_group");
    private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("messaging.event_id");
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("messaging.event_type");
    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("messaging.correlation_id");
    private static final AttributeKey<String> CAUSATION_ID = AttributeKey.stringKey("messaging.causation_id");

    private final Tracer tracer;

    public OtelEventConsumerTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public SpanScope start(String stream, String consumerGroup, EventEnvelope envelope) {
        Span span = tracer.spanBuilder(envelope.eventType() + " process")
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute(MESSAGING_SYSTEM, "redis")
            .setAttribute(MESSAGING_OPERATION, "process")
            .setAttribute(MESSAGING_DESTINATION, stream)
            .setAttribute(STREAM, stream)
            .setAttribute(CONSUMER_GROUP, consumerGroup)
            .setAttribute(EVENT_ID, envelope.eventId())
            .setAttribute(EVENT_TYPE, envelope.eventType())
            .setAttribute(CORRELATION_ID, envelope.correlationId())
            .startSpan();
        if (envelope.causationId() != null && !envelope.causationId().isBlank()) {
            span.setAttribute(CAUSATION_ID, envelope.causationId());
        }
        return new OtelSpanScope(span, span.makeCurrent());
    }

    private static final class OtelSpanScope extends SpanScope {
        private final Span span;

        private OtelSpanScope(Span span, Scope scope) {
            super(span, scope);
            this.span = span;
        }

        @Override
        public void recordException(RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getClass().getSimpleName());
        }

        @Override
        public void markError(String description) {
            span.setStatus(StatusCode.ERROR, description);
        }
    }
}
