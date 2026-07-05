package com.trainticket.adminaudit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "none", matchIfMissing = true)
public class NoOpRuntimeTracer implements RuntimeTracer {
    @Override
    public void requestStarted(RequestTraceContext context) {
        // Default seam intentionally does nothing; services can provide a RuntimeTracer bean to opt in.
    }

    @Override
    public void requestCompleted(RequestTraceContext context, int statusCode) {
        // Default seam intentionally does nothing; services can provide a RuntimeTracer bean to opt in.
    }
}
