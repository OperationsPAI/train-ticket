package com.trainticket.financesettlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = RuntimeTracer.class, ignored = NoOpRuntimeTracer.class)
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
