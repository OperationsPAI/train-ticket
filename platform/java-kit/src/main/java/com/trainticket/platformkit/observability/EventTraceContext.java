package com.trainticket.platformkit.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the W3C trace context associated with the currently active span.
 * Trace propagation is observability-only; failures or disabled OpenTelemetry
 * must leave the event wire shape unchanged.
 */
public record EventTraceContext(String traceparent, String tracestate) {
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.put(key, value);
        }
    };

    public static EventTraceContext current() {
        if (!Span.current().getSpanContext().isValid()) {
            return empty();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        try {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, SETTER);
        } catch (RuntimeException ignored) {
            return empty();
        }
        String traceparent = blankToNull(carrier.get(TRACEPARENT));
        if (traceparent == null) {
            return empty();
        }
        return new EventTraceContext(traceparent, blankToNull(carrier.get(TRACESTATE)));
    }

    public static EventTraceContext empty() {
        return new EventTraceContext(null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
