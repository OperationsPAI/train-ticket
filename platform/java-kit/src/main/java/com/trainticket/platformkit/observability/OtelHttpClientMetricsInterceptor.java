package com.trainticket.platformkit.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Outbound HTTP call duration, recorded against the OpenTelemetry API directly.
 *
 * {@code http.client.request.duration} is the name and {@code s} the unit the
 * HTTP semantic conventions define, matching what the Node instrumentation in
 * ts-kit and the Python instrumentation report for the same quantity. As with
 * the server histogram there is no separate call counter: a histogram carries
 * its own count.
 *
 * Installed on every {@link org.springframework.web.client.RestClient.Builder}
 * by {@link RestClientMetricsCustomizer}, beside the trace-propagation
 * interceptor, so the twelve Java services' service-to-service calls are covered
 * without an edit in any of them.
 *
 * The attributes are the convention's required set for a client call:
 * {@code server.address} and {@code server.port} identify the callee and
 * {@code http.request.method} the verb. Deliberately absent is
 * {@code url.full} -- it carries the path, and a path holds ids, so it would
 * produce one series per order id. Which route was called is answerable from the
 * server side of the same call, where {@code http.route} is the matched pattern
 * rather than the interpolated path.
 */
public final class OtelHttpClientMetricsInterceptor implements ClientHttpRequestInterceptor {
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    /**
     * The methods RFC 9110 registers, plus PATCH. An unrecognized method is
     * reported as {@code _OTHER}, as the convention requires.
     */
    private static final List<String> KNOWN_METHODS = List.of(
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH");
    private static final String OTHER_METHOD = "_OTHER";

    private final DoubleHistogram requestDuration;

    public OtelHttpClientMetricsInterceptor(Meter meter) {
        this.requestDuration = meter.histogramBuilder("http.client.request.duration")
            .setDescription("Duration of HTTP client requests.")
            .setUnit("s")
            .build();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        // Nanosecond source: the duration is reported in seconds, and
        // currentTimeMillis is both coarser than the calls being measured and
        // subject to clock adjustment, which can produce a negative sample.
        long startedAt = System.nanoTime();
        ClientHttpResponse response = null;
        String errorType = null;
        try {
            response = execution.execute(request, body);
            return response;
        } catch (IOException | RuntimeException exception) {
            // A connect or read timeout never yields a status, so without this the
            // calls that matter most when a callee is saturated would be the ones
            // missing from the histogram.
            errorType = exception.getClass().getName();
            throw exception;
        } finally {
            double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            requestDuration.record(seconds, attributes(request, response, errorType));
        }
    }

    private static Attributes attributes(HttpRequest request, ClientHttpResponse response, String errorType) {
        URI uri = request.getURI();
        AttributesBuilder attributes = Attributes.builder()
            .put(HTTP_METHOD, method(request));
        if (uri.getHost() != null) {
            attributes.put(SERVER_ADDRESS, uri.getHost());
        }
        int port = port(uri);
        if (port > 0) {
            attributes.put(SERVER_PORT, port);
        }
        if (errorType != null) {
            attributes.put(ERROR_TYPE, errorType);
            return attributes.build();
        }
        int status = statusCode(response);
        if (status > 0) {
            attributes.put(HTTP_STATUS, status);
            if (status >= 500) {
                attributes.put(ERROR_TYPE, String.valueOf(status));
            }
        }
        return attributes.build();
    }

    /**
     * The scheme's default port when the URI states none, which is what the
     * convention asks for: a series keyed on an absent port and one keyed on 80
     * would split the same callee in two.
     */
    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return switch (String.valueOf(uri.getScheme())) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static int statusCode(ClientHttpResponse response) {
        if (response == null) {
            return -1;
        }
        try {
            return response.getStatusCode().value();
        } catch (IOException exception) {
            // Reading the status can itself fail on a truncated response. The
            // duration is still worth recording, so the attribute is dropped
            // rather than the sample.
            return -1;
        }
    }

    private static String method(HttpRequest request) {
        String method = request.getMethod().name();
        return KNOWN_METHODS.contains(method) ? method : OTHER_METHOD;
    }
}
