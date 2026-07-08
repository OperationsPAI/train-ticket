package com.trainticket.platformkit.observability;

import com.trainticket.platformkit.messaging.EventEnvelope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public interface EventConsumerTracer {
    SpanScope start(String stream, String consumerGroup, EventEnvelope envelope);

    class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void recordException(RuntimeException exception) {
        }

        public void markError(String description) {
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
