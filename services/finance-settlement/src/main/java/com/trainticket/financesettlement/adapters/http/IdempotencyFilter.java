package com.trainticket.financesettlement.adapters.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.RequestContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private final ConcurrentMap<String, CachedResponse> responses = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        if (idempotencyKey == null) {
            writeError(response, request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key header is required");
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String fingerprint = fingerprint(request, body);
        CachedResponse cached = responses.get(idempotencyKey);
        if (cached != null) {
            if (!cached.fingerprint().equals(fingerprint)) {
                writeError(response, request, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was reused with a different request");
                return;
            }
            replay(response, cached);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(new CachedBodyRequest(request, body), wrappedResponse);
        responses.putIfAbsent(idempotencyKey, new CachedResponse(
            fingerprint,
            wrappedResponse.getStatus(),
            wrappedResponse.getContentType(),
            wrappedResponse.getContentAsByteArray()
        ));
        wrappedResponse.copyBodyToResponse();
    }

    private void replay(HttpServletResponse response, CachedResponse cached) throws IOException {
        response.setStatus(cached.status());
        if (cached.contentType() != null) {
            response.setContentType(cached.contentType());
        }
        response.getOutputStream().write(cached.body());
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message, correlationId(request), Map.of()));
    }

    private static String fingerprint(HttpServletRequest request, byte[] body) {
        String source = request.getMethod() + " " + request.getRequestURI() + "?" + Optional.ofNullable(request.getQueryString()).orElse("") + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
        return attribute instanceof String value ? value : Optional.ofNullable(request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER)).orElse("");
    }

    private record CachedResponse(String fingerprint, int status, String contentType, byte[] body) {}

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return delegate.read();
                }
            };
        }
    }
}
