package com.trainticket.platformkit.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP server metrics, recorded against the OpenTelemetry API directly.
 *
 * The names, units and attributes are the ones the HTTP semantic conventions
 * define, so the two instruments here are {@code http.server.request.duration}
 * (seconds) and {@code http.server.active_requests}. Micrometer's own
 * {@code http.server.requests} is not used: this kit registers no Spring Boot
 * actuator observation, and the convention names are what a reader querying
 * across languages will expect, since every other language in this repository
 * reports HTTP server metrics under them too.
 *
 * The request count is not a separate instrument. A histogram carries its own
 * count, so {@code http.server.request.duration} answers both "how long" and
 * "how many"; adding a counter beside it would be a second series saying the
 * same thing.
 *
 * Route rather than raw path in the attributes: {@code url.path} has unbounded
 * cardinality because it contains ids, and one series per order id would grow
 * without limit. The convention fixes this by specifying {@code http.route}, the
 * matched pattern.
 */
public final class OtelHttpServerMetricsFilter extends OncePerRequestFilter implements Ordered {
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    /**
     * The methods RFC 9110 registers, plus PATCH.
     *
     * The convention requires an unrecognized method to be reported as
     * {@code _OTHER} rather than passed through: a client is free to send any
     * token there, and a series per invented method name is a cardinality leak
     * reachable from outside the cluster.
     */
    private static final List<String> KNOWN_METHODS = List.of(
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH");
    private static final String OTHER_METHOD = "_OTHER";

    private final DoubleHistogram requestDuration;
    private final LongUpDownCounter activeRequests;

    public OtelHttpServerMetricsFilter(Meter meter) {
        this.requestDuration = meter.histogramBuilder("http.server.request.duration")
            .setDescription("Duration of HTTP server requests.")
            .setUnit("s")
            .build();
        this.activeRequests = meter.upDownCounterBuilder("http.server.active_requests")
            .setDescription("Number of active HTTP server requests.")
            .setUnit("{request}")
            .build();
    }

    /**
     * Just inside the tracing filter, so a request counted here is one that also
     * produced a span and the two signals cover the same set.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 21;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String method = method(request);
        Attributes inFlight = Attributes.of(HTTP_METHOD, method);
        activeRequests.add(1, inFlight);
        // Nanosecond source: the duration is reported in seconds, and
        // currentTimeMillis is both coarser than the requests being measured and
        // subject to clock adjustment, which can produce a negative sample.
        long startedAt = System.nanoTime();
        String errorType = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable exception) {
            errorType = exception.getClass().getName();
            throw exception;
        } finally {
            activeRequests.add(-1, inFlight);
            double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            requestDuration.record(seconds, completedAttributes(request, response, method, errorType));
        }
    }

    private static Attributes completedAttributes(HttpServletRequest request, HttpServletResponse response,
                                                  String method, String errorType) {
        AttributesBuilder attributes = Attributes.builder()
            .put(HTTP_METHOD, method)
            .put(HTTP_STATUS, response.getStatus());
        String route = route(request);
        if (route != null) {
            attributes.put(HTTP_ROUTE, route);
        }
        // error.type is set for a thrown exception and for a 5xx, which is what
        // lets an error rate be read off this one instrument.
        if (errorType != null) {
            attributes.put(ERROR_TYPE, errorType);
        } else if (response.getStatus() >= 500) {
            attributes.put(ERROR_TYPE, String.valueOf(response.getStatus()));
        }
        return attributes.build();
    }

    /**
     * The matched handler pattern, or null when the request matched no handler.
     *
     * Read after the chain has run because the pattern is a request attribute
     * Spring's HandlerMapping writes while dispatching, so it does not exist
     * before then.
     *
     * A miss yields no attribute rather than the raw URI. The convention permits
     * omission for exactly this case, and it has to be taken here: an unrouted
     * request carries a path an outside caller chose, so recording it would let
     * anyone reaching the ingress create unbounded series by requesting random
     * paths.
     */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route && !route.isBlank()) {
            return route;
        }
        return null;
    }

    private static String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && KNOWN_METHODS.contains(method) ? method : OTHER_METHOD;
    }
}
