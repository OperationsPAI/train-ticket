package com.trainticket.adminaudit;

public interface RuntimeTracer {
    void requestStarted(RequestTraceContext context);

    void requestCompleted(RequestTraceContext context, int statusCode);
}
