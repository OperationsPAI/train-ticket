package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;

public final class OtelEventConsumerTracer implements EventConsumerTracer {
    private static final TextMapGetter<EventEnvelope> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(EventEnvelope carrier) {
            return carrier.tracestate() == null ? List.of("traceparent") : List.of("traceparent", "tracestate");
        }

        @Override
        public String get(EventEnvelope carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            if ("traceparent".equals(key)) {
                return carrier.traceparent();
            }
            if ("tracestate".equals(key)) {
                return carrier.tracestate();
            }
            return null;
        }
    };
    private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> MESSAGING_OPERATION = AttributeKey.stringKey("messaging.operation");
    private static final AttributeKey<String> MESSAGING_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> STREAM = AttributeKey.stringKey("messaging.stream");
    private static final AttributeKey<String> CONSUMER_GROUP = AttributeKey.stringKey("messaging.consumer_group");
    private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("messaging.event_id");
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("messaging.event_type");
    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("messaging.correlation_id");
    private static final AttributeKey<String> CAUSATION_ID = AttributeKey.stringKey("messaging.causation_id");

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OtelEventConsumerTracer(OpenTelemetry openTelemetry, Tracer tracer) {
        this.openTelemetry = openTelemetry;
        this.tracer = tracer;
    }

    public OtelEventConsumerTracer(Tracer tracer) {
        this(OpenTelemetry.noop(), tracer);
    }

    @Override
    public SpanScope start(String stream, String consumerGroup, EventEnvelope envelope) {
        Span span = tracer.spanBuilder(envelope.eventType() + " process")
            .setParent(parentContext(envelope))
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

    private Context parentContext(EventEnvelope envelope) {
        if (envelope.traceparent() == null || envelope.traceparent().isBlank()) {
            return Context.current();
        }
        try {
            Context extracted = openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), envelope, GETTER);
            return Span.fromContext(extracted).getSpanContext().isValid() ? extracted : Context.current();
        } catch (RuntimeException ignored) {
            return Context.current();
        }
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
