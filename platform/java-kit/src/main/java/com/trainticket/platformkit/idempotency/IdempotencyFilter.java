package com.trainticket.platformkit.idempotency;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.CanonicalErrorWriter;
import com.trainticket.platformkit.http.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyStore store;
    private final CanonicalErrorWriter errorWriter;

    public IdempotencyFilter(IdempotencyStore store, CanonicalErrorWriter errorWriter) {
        this.store = store;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String idempotencyKey = Optional.ofNullable(request.getHeader(IDEMPOTENCY_KEY_HEADER))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .orElse(null);
        if (!UuidV7.isValid(idempotencyKey)) {
            errorWriter.write(response, ApiErrorCode.VALIDATION_FAILED, "Idempotency-Key header must be a UUID v7", CorrelationIds.from(request));
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String fingerprint = RequestFingerprint.of(request, body);
        Optional<IdempotencyStore.StoredResponse> existing = store.find(idempotencyKey);
        if (existing.isPresent()) {
            replayOrReject(request, response, fingerprint, existing.get());
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), wrappedResponse);
        IdempotencyStore.StoredResponse stored = new IdempotencyStore.StoredResponse(
            fingerprint,
            wrappedResponse.getStatus(),
            wrappedResponse.getContentType(),
            responseHeaders(wrappedResponse),
            wrappedResponse.getContentAsByteArray()
        );
        IdempotencyStore.StoredResponse visible = store.saveIfAbsent(idempotencyKey, stored);
        if (visible != stored) {
            wrappedResponse.reset();
            replayOrReject(request, wrappedResponse, fingerprint, visible);
        }
        wrappedResponse.copyBodyToResponse();
    }

    private void replayOrReject(HttpServletRequest request, HttpServletResponse response, String fingerprint, IdempotencyStore.StoredResponse stored) throws IOException {
        if (!stored.fingerprint().equals(fingerprint)) {
            errorWriter.write(response, ApiErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with a different request body", CorrelationIds.from(request));
            return;
        }
        response.setStatus(stored.status());
        for (Map.Entry<String, List<String>> header : stored.headers().entrySet()) {
            if ("Content-Type".equalsIgnoreCase(header.getKey()) || "Content-Length".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            for (String value : header.getValue()) {
                response.addHeader(header.getKey(), value);
            }
        }
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.getOutputStream().write(stored.body());
    }

    private static Map<String, List<String>> responseHeaders(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String headerName : response.getHeaderNames()) {
            Collection<String> values = response.getHeaders(headerName);
            headers.put(headerName, List.copyOf(values));
        }
        return headers;
    }
}
