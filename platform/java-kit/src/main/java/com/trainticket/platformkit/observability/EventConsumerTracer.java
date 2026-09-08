package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public interface EventConsumerTracer {
    SpanScope start(String stream, String consumerGroup, EventEnvelope envelope);

    class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        // Bound here rather than in each implementation so both the OTel-enabled
        // and no-op tracers put trace_id/span_id on every log line a handler
        // emits. Event handlers are where correlation matters most: an event's
        // trace begins in an HTTP request in another service, and without the ids
        // in the MDC there is no way to join a handler's log line back to it.
        private final TraceLoggingContext.Handle mdc;

        SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
            this.mdc = TraceLoggingContext.from(span);
        }

        public void recordException(RuntimeException exception) {
        }

        public void markError(String description) {
        }

        @Override
        public void close() {
            mdc.close();
            scope.close();
            span.end();
        }
    }
}
