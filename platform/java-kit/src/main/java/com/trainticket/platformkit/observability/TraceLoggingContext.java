package com.trainticket.platformkit.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Publishes the current span's ids into SLF4J's MDC so log lines can be joined
 * to traces.
 *
 * WHY THIS EXISTS
 * ---------------
 * The platform had full trace propagation (W3C traceparent across HTTP and
 * through event envelopes) and structured logs, and no way to get from one to the
 * other: nothing put a trace id in the MDC and no service's log pattern printed
 * one. So a log line saying a refund was priced at zero could not be tied to the
 * trace that produced it, and a trace showing a slow or failed span could not be
 * tied to the log line explaining why. Each half was individually complete and
 * the pair was much less useful than either implies.
 *
 * Diagnosing the zero-refund chain is what made this concrete: five services,
 * each taking a silent early return, and the only way to correlate them was to
 * match wall-clock timestamps by hand across five `kubectl logs` invocations.
 *
 * KEYS
 * `trace_id` / `span_id` are the names the OpenTelemetry Logback and Log4j
 * appenders use, so a service that later adopts one of those gets the same field
 * names rather than a second convention.
 *
 * The keys are only set when there is a valid span. An unsampled or absent span
 * yields an all-zero trace id, and putting that in the MDC is worse than putting
 * nothing: it looks like a real id and joins every unrelated line together.
 */
public final class TraceLoggingContext {
    public static final String TRACE_ID_KEY = "trace_id";
    public static final String SPAN_ID_KEY = "span_id";

    private TraceLoggingContext() {
    }

    /**
     * Binds the current span's ids to the MDC and returns a handle that restores
     * whatever was there before.
     *
     * The previous values are restored rather than simply cleared, because these
     * scopes nest: an event handler that makes an HTTP call would otherwise clear
     * the outer consumer scope's ids on the inner call's way out, and the rest of
     * the handler would log without a trace id.
     *
     * Intended for try-with-resources:
     * <pre>
     *   try (var ignored = TraceLoggingContext.current()) { ... }
     * </pre>
     */
    public static Handle current() {
        return from(Span.current());
    }

    public static Handle from(Span span) {
        if (span == null) {
            return Handle.NOOP;
        }
        SpanContext context = span.getSpanContext();
        if (!context.isValid()) {
            return Handle.NOOP;
        }
        String previousTrace = MDC.get(TRACE_ID_KEY);
        String previousSpan = MDC.get(SPAN_ID_KEY);
        MDC.put(TRACE_ID_KEY, context.getTraceId());
        MDC.put(SPAN_ID_KEY, context.getSpanId());
        return new Handle(true, previousTrace, previousSpan);
    }

    /** Restores the MDC state that existed before the matching bind. */
    public static final class Handle implements AutoCloseable {
        private static final Handle NOOP = new Handle(false, null, null);

        /**
         * False when nothing was bound. close() must then do nothing at all: an
         * unconditional restore would clear an enclosing scope's ids, so a handler
         * that opened a no-op inner scope would log the rest of its work without a
         * trace id.
         */
        private final boolean bound;
        private final String previousTrace;
        private final String previousSpan;

        private Handle(boolean bound, String previousTrace, String previousSpan) {
            this.bound = bound;
            this.previousTrace = previousTrace;
            this.previousSpan = previousSpan;
        }

        @Override
        public void close() {
            if (!bound) {
                return;
            }
            restore(TRACE_ID_KEY, previousTrace);
            restore(SPAN_ID_KEY, previousSpan);
        }

        private static void restore(String key, String previous) {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }

    /** The current trace and span ids, for callers that want them as data. */
    public static Map<String, String> currentIds() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return Map.of();
        }
        return Map.of(TRACE_ID_KEY, context.getTraceId(), SPAN_ID_KEY, context.getSpanId());
    }
}
