package com.trainticket.platformkit.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public final class HttpTracePropagationInterceptor implements ClientHttpRequestInterceptor {
    private static final TextMapSetter<HttpRequest> SETTER = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.getHeaders().set(key, value);
        }
    };

    private final OpenTelemetry openTelemetry;

    public HttpTracePropagationInterceptor(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), request, SETTER);
        return execution.execute(request, body);
    }
}
