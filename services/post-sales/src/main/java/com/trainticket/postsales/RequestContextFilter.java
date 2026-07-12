package com.trainticket.postsales;

import com.trainticket.platformkit.http.CorrelationIds;
import com.trainticket.platformkit.messaging.PrefixedIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("postSalesRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final RuntimeTracer tracer;

    public RequestContextFilter(RuntimeTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = headerOrGenerated(request, REQUEST_ID_HEADER);
        String correlationId = headerOrGenerated(request, CORRELATION_ID_HEADER, "corr-");
        RequestTraceContext context = new RequestTraceContext(
            requestId,
            correlationId,
            request.getMethod(),
            request.getRequestURI()
        );
        request.setAttribute("requestId", requestId);
        request.setAttribute("correlationId", correlationId);
        request.setAttribute(CorrelationIds.CORRELATION_ATTRIBUTE, correlationId);

        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        tracer.requestStarted(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            tracer.requestCompleted(context, response.getStatus());
            MDC.remove("requestId");
            MDC.remove("correlationId");
        }
    }

    private static String headerOrGenerated(HttpServletRequest request, String headerName) {
        return headerOrGenerated(request, headerName, "");
    }

    private static String headerOrGenerated(HttpServletRequest request, String headerName, String generatedPrefix) {
        return Optional.ofNullable(request.getHeader(headerName))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .orElseGet(() -> generatedPrefix.isEmpty() ? com.trainticket.platformkit.idempotency.UuidV7.generate() : PrefixedIds.newCorrelationId());
    }
}
