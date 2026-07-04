package com.trainticket.financesettlement;

public interface RuntimeTracer {
    void requestStarted(RequestTraceContext context);

    void requestCompleted(RequestTraceContext context, int statusCode);
}
