package com.trainticket.adminaudit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.adminaudit.adapters.messaging.AdminAuditSubscriptionRunner;
import com.trainticket.adminaudit.application.AdminAuditInboundEventHandler;
import com.trainticket.adminaudit.application.AdminAuditService;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.application.ports.InMemoryAdminAuditRepository;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import com.trainticket.platformkit.messaging.EventEnvelope;

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
        assertEquals("admin-audit", profile.serviceId());
    }

    @Test
    void runtimeEndpointsUseConsistentShape() {
        HealthController controller = new HealthController(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );

        assertEquals("live", controller.live().get("status"));
        assertEquals("ready", controller.ready().getBody().get("status"));
        Map<String, Object> metadata = controller.metadata();
        assertEquals("2026-01-01T00:00:00Z", metadata.get("generatedAt"));
        assertEquals("admin-audit", assertInstanceOf(ServiceProfile.class, metadata.get("service")).serviceId());
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
        // A missing correlation header mints a canonical corr-<uuid7> id
        // instead of reusing the request id (shared-primitives ruling).
        String correlationId = response.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        assertNotNull(correlationId);
        assertTrue(correlationId.startsWith("corr-"));
    }

    @Test
    void legacyAclEventsAreAuditedAndDoNotPoisonHandler() {
        InMemoryAdminAuditRepository repository = new InMemoryAdminAuditRepository();
        List<EventEnvelope> published = new ArrayList<>();
        EventPublisher publisher = published::add;
        AdminAuditService service = new AdminAuditService(
            repository,
            publisher,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        AdminAuditInboundEventHandler handler = new AdminAuditInboundEventHandler(service);
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8000-000000000001",
            "LegacyCommandMapped",
            Instant.parse("2026-01-01T00:00:00Z"),
            "corr-0194f2e0-7b3e-7610-8000-000000000002",
            "cmd-0194f2e0-7b3e-7610-8000-000000000003",
            "legacy-acl",
            1,
            Map.of(
                "legacyOperation", "PRESERVE",
                "outcome", "SUCCEEDED",
                "operatorRef", "op-1",
                "sourceRef", "0194f2e0-7b3e-7610-8000-000000000004"
            )
        );

        EventSubscriber.HandlerResult result = handler.handle(envelope);

        assertEquals(EventSubscriber.HandlerResult.SUCCESS, result);
        assertEquals(1, repository.countAuditEntries("0194f2e0-7b3e-7610-8000-000000000004"));
        assertEquals("AuditEntryRecorded", published.getFirst().eventType());
        assertTrue(AdminAuditSubscriptionRunner.SUBSCRIBED_STREAMS.contains("events:legacy-acl"));
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
