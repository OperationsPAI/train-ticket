package com.trainticket.bookingorchestration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApplicationTest {
    @Test
    void healthEndpointReturnsServiceProfile() {
        Map<String, Object> response = new HealthController().health();

        assertEquals("ok", response.get("status"));
        ServiceProfile profile = assertInstanceOf(ServiceProfile.class, response.get("service"));
        assertEquals("booking-orchestration", profile.serviceId());
    }

    @Test
    void runtimeEndpointsUseConsistentShape() {
        HealthController controller = new HealthController(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );

        assertEquals("live", controller.live().get("status"));
        assertEquals("ready", controller.ready().get("status"));
        Map<String, Object> metadata = controller.metadata();
        assertEquals("2026-01-01T00:00:00Z", metadata.get("generatedAt"));
        assertEquals("booking-orchestration", assertInstanceOf(ServiceProfile.class, metadata.get("service")).serviceId());
    }

    @Test
    void requestContextFilterPropagatesExistingIds() throws ServletException, IOException {
        RecordingTracer tracer = new RecordingTracer();
        RequestContextFilter filter = new RequestContextFilter(tracer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, "request-123");
        request.addHeader(RequestContextFilter.CORRELATION_ID_HEADER, "correlation-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("request-123", response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
        assertEquals("correlation-456", response.getHeader(RequestContextFilter.CORRELATION_ID_HEADER));
        assertEquals(1, tracer.started.size());
        assertEquals("request-123", tracer.started.getFirst().requestId());
        assertEquals("correlation-456", tracer.started.getFirst().correlationId());
        assertEquals(200, tracer.completedStatusCodes.getFirst());
    }

    @Test
    void requestContextFilterGeneratesMissingIdsAndNoOpTracerIsSafe() throws ServletException, IOException {
        RequestContextFilter filter = new RequestContextFilter(new NoOpRuntimeTracer());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/metadata");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter.doFilter(request, response, new MockFilterChain()));

        String requestId = response.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
        assertNotNull(requestId);
        assertFalse(requestId.isBlank());
        String correlationId = response.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        assertNotNull(correlationId);
        assertTrue(correlationId.startsWith("corr-"));
    }

    private static final class RecordingTracer implements RuntimeTracer {
        private final List<RequestTraceContext> started = new ArrayList<>();
        private final List<Integer> completedStatusCodes = new ArrayList<>();

        @Override
        public void requestStarted(RequestTraceContext context) {
            started.add(context);
        }

        @Override
        public void requestCompleted(RequestTraceContext context, int statusCode) {
            completedStatusCodes.add(statusCode);
        }
    }
}
