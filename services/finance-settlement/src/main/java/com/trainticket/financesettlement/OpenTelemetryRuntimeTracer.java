package com.trainticket.financesettlement;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
// Spring relaxed binding also accepts OTEL_TRACES_EXPORTER=otlp.
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
public class OpenTelemetryRuntimeTracer implements RuntimeTracer {
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> LEGACY_HTTP_METHOD = AttributeKey.stringKey("http.method");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey("http.request_id");
    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("http.correlation_id");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> LEGACY_HTTP_STATUS = AttributeKey.longKey("http.status_code");
    static final String REQUEST_ATTRIBUTE = OpenTelemetryRuntimeTracer.class.getName() + ".span";

    private final Tracer tracer;
    private final HttpServletRequest request;
    private final String serviceName;

    public OpenTelemetryRuntimeTracer(HttpServletRequest request) {
        this.request = request;
        this.serviceName = Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .orElse(Application.profile().serviceId());
        this.tracer = GlobalOpenTelemetry.getTracer(serviceName);
    }

    @Override
    public void requestStarted(RequestTraceContext context) {
        Span span = tracer.spanBuilder(context.method() + " " + context.path())
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(SERVICE_NAME, serviceName)
            .setAttribute(HTTP_METHOD, context.method())
            .setAttribute(LEGACY_HTTP_METHOD, context.method())
            .setAttribute(URL_PATH, context.path())
            .setAttribute(HTTP_ROUTE, context.path())
            .setAttribute(REQUEST_ID, context.requestId())
            .setAttribute(CORRELATION_ID, context.correlationId())
            .startSpan();
        request.setAttribute(REQUEST_ATTRIBUTE, span);
    }

    @Override
    public void requestCompleted(RequestTraceContext context, int statusCode) {
        Object current = request.getAttribute(REQUEST_ATTRIBUTE);
        if (current instanceof Span span) {
            span.setAttribute(HTTP_STATUS, statusCode);
            span.setAttribute(LEGACY_HTTP_STATUS, statusCode);
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR, "http status " + statusCode);
            }
            span.end();
            request.removeAttribute(REQUEST_ATTRIBUTE);
        }
    }
}
