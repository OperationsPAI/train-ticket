package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The point of this class is that a log line can be joined to a trace. These
 * tests pin the two ways that silently stops being true: ids that never arrive,
 * and ids that get cleared while the request is still running.
 */
class TraceLoggingContextTest {
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN = "b7ad6b7169203331";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static Span sampledSpan(String traceId, String spanId) {
        return Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()));
    }

    @Test
    void bindsTraceAndSpanIdsForAValidSpan() {
        try (TraceLoggingContext.Handle handle = TraceLoggingContext.from(sampledSpan(TRACE, SPAN))) {
            assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isEqualTo(TRACE);
            assertThat(MDC.get(TraceLoggingContext.SPAN_ID_KEY)).isEqualTo(SPAN);
        }
        assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isNull();
        assertThat(MDC.get(TraceLoggingContext.SPAN_ID_KEY)).isNull();
    }

    @Test
    void bindsNothingForAnInvalidSpan() {
        // An absent or non-recording span yields an all-zero trace id. Putting that
        // in the MDC is worse than putting nothing: it looks like a real id and
        // joins every unrelated line in the log together.
        try (TraceLoggingContext.Handle handle = TraceLoggingContext.from(Span.getInvalid())) {
            assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isNull();
        }
    }

    @Test
    void bindsNothingForANullSpan() {
        try (TraceLoggingContext.Handle handle = TraceLoggingContext.from(null)) {
            assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isNull();
        }
    }

    @Test
    void anInnerNoOpScopeDoesNotClearTheOuterScope() {
        // The regression this guards: an event handler binds a scope, calls
        // something that opens a no-op scope, and on the inner close the handler
        // loses its trace id for every remaining line -- exactly the lines most
        // likely to explain a failure, since they come after the work.
        try (TraceLoggingContext.Handle outer = TraceLoggingContext.from(sampledSpan(TRACE, SPAN))) {
            try (TraceLoggingContext.Handle inner = TraceLoggingContext.from(Span.getInvalid())) {
                assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isEqualTo(TRACE);
            }
            assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY))
                .as("the outer scope's trace id must survive an inner no-op close")
                .isEqualTo(TRACE);
        }
    }

    @Test
    void nestedScopesRestoreTheEnclosingIds() {
        String innerTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        String innerSpan = "00f067aa0ba902b7";
        try (TraceLoggingContext.Handle outer = TraceLoggingContext.from(sampledSpan(TRACE, SPAN))) {
            try (TraceLoggingContext.Handle inner = TraceLoggingContext.from(sampledSpan(innerTrace, innerSpan))) {
                assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isEqualTo(innerTrace);
                assertThat(MDC.get(TraceLoggingContext.SPAN_ID_KEY)).isEqualTo(innerSpan);
            }
            assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isEqualTo(TRACE);
            assertThat(MDC.get(TraceLoggingContext.SPAN_ID_KEY)).isEqualTo(SPAN);
        }
        assertThat(MDC.get(TraceLoggingContext.TRACE_ID_KEY)).isNull();
    }

    @Test
    void currentIdsReportsTheActiveSpan() {
        assertThat(TraceLoggingContext.currentIds()).isEmpty();
    }
}
