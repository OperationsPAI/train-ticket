package com.trainticket.journeyorder;

public interface RuntimeTracer {
    void requestStarted(RequestTraceContext context);

    void requestCompleted(RequestTraceContext context, int statusCode);
}
