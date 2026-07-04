package com.trainticket.bookingorchestration;

public interface RuntimeTracer {
    void requestStarted(RequestTraceContext context);

    void requestCompleted(RequestTraceContext context, int statusCode);
}
