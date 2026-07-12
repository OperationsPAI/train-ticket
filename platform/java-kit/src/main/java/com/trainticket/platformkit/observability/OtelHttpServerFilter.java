package com.trainticket.platformkit.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public final class OtelHttpServerFilter extends OncePerRequestFilter implements Ordered {
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> LEGACY_HTTP_METHOD = AttributeKey.stringKey("http.method");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> LEGACY_HTTP_STATUS = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey("http.request_id");
    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("http.correlation_id");
    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return carrier.getHeaderNames()::asIterator;
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OtelHttpServerFilter(OpenTelemetry openTelemetry, Tracer tracer) {
        this.openTelemetry = openTelemetry;
        this.tracer = tracer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        Context parentContext = openTelemetry.getPropagators()
            .getTextMapPropagator()
            .extract(Context.current(), request, GETTER);
        Span span = tracer.spanBuilder(request.getMethod() + " " + request.getRequestURI())
            .setParent(parentContext)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(HTTP_METHOD, request.getMethod())
            .setAttribute(LEGACY_HTTP_METHOD, request.getMethod())
            .setAttribute(URL_PATH, request.getRequestURI())
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            filterChain.doFilter(request, response);
        } catch (Throwable exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getClass().getSimpleName());
            throw exception;
        } finally {
            setIfPresent(span, REQUEST_ID, response.getHeader("X-Request-Id"));
            setIfPresent(span, CORRELATION_ID, response.getHeader("X-Correlation-Id"));
            span.setAttribute(HTTP_ROUTE, route(request));
            span.setAttribute(HTTP_STATUS, response.getStatus());
            span.setAttribute(LEGACY_HTTP_STATUS, response.getStatus());
            if (response.getStatus() >= 500) {
                span.setStatus(StatusCode.ERROR, "http status " + response.getStatus());
            }
            span.end();
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route && !route.isBlank()) {
            return route;
        }
        return request.getRequestURI();
    }

    private static void setIfPresent(Span span, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }
}
