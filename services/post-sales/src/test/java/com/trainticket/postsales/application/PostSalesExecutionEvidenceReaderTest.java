package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.postsales.domain.DomainRuleViolation;
import com.trainticket.postsales.domain.PostSalesCase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PostSalesExecutionEvidenceReaderTest {
    @Test
    void acceptsLinkedVoidedCancelledReleasedAndRefundEvidenceUsingOnlyGets() throws Exception {
        PostSalesCase source = PostSalesExecutionRedriveServiceTest.executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        try (FactServer server = new FactServer(source, "CANCELLED", 404, null, approved)) {
            var evidence = reader(source, server).read(source.caseId(), "corr-evidence");

            assertEquals(source.caseId(), evidence.caseId());
            assertEquals("ent-redrive", evidence.entitlements().getFirst().entitlementId());
            assertEquals("sb-redrive", evidence.bookings().getFirst().segmentBookingId());
            assertTrue(evidence.capacity().getFirst().absentAfterCancellation());
            assertEquals(approved, evidence.refunds().getFirst().amount().toMinorUnits());
            assertEquals(List.of("GET", "GET", "GET", "GET"), server.methods);
        }
    }

    @Test
    void doesNotTreatHold404AsReleaseWithoutCancelledBooking() throws Exception {
        PostSalesCase source = PostSalesExecutionRedriveServiceTest.executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        try (FactServer server = new FactServer(source, "CONFIRMED", 404, null, approved)) {
            DomainRuleViolation error = assertThrows(DomainRuleViolation.class,
                () -> reader(source, server).read(source.caseId(), "corr-evidence"));

            assertTrue(error.getMessage().contains("booking"));
        }
    }

    @Test
    void existingConfirmedHoldIsNotRecordedAsReleased() throws Exception {
        PostSalesCase source = PostSalesExecutionRedriveServiceTest.executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        try (FactServer server = new FactServer(source, "CANCELLED", 200, "CONFIRMED", approved)) {
            DomainRuleViolation error = assertThrows(DomainRuleViolation.class,
                () -> reader(source, server).read(source.caseId(), "corr-evidence"));

            assertTrue(error.getMessage().contains("capacity hold"));
        }
    }

    @Test
    void refundEvidenceMustMatchFrozenAllocation() throws Exception {
        PostSalesCase source = PostSalesExecutionRedriveServiceTest.executingRefund();
        long approved = source.decision().amountSnapshot().refundAmount().toMinorUnits();
        try (FactServer server = new FactServer(source, "CANCELLED", 404, null, approved - 1)) {
            DomainRuleViolation error = assertThrows(DomainRuleViolation.class,
                () -> reader(source, server).read(source.caseId(), "corr-evidence"));

            assertTrue(error.getMessage().contains("approved amount"));
        }
    }

    private static PostSalesExecutionEvidenceReader reader(PostSalesCase source, FactServer server) {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        repository.save(source);
        PostSalesApplicationService cases = new PostSalesApplicationService(repository, ignored -> { }, ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(), Clock.fixed(Instant.parse("2026-09-25T08:10:00Z"), ZoneOffset.UTC));
        return new PostSalesExecutionEvidenceReader(cases, new ObjectMapper().findAndRegisterModules(), RestClient.builder(),
            server.base(), server.base(), server.base(), server.base());
    }

    private static final class FactServer implements AutoCloseable {
        private final HttpServer server;
        private final PostSalesCase source;
        private final String bookingStatus;
        private final int holdStatusCode;
        private final String holdState;
        private final long refundMinorUnits;
        private final List<String> methods = new CopyOnWriteArrayList<>();

        FactServer(PostSalesCase source, String bookingStatus, int holdStatusCode, String holdState, long refundMinorUnits) throws Exception {
            this.source = source;
            this.bookingStatus = bookingStatus;
            this.holdStatusCode = holdStatusCode;
            this.holdState = holdState;
            this.refundMinorUnits = refundMinorUnits;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            methods.add(exchange.getRequestMethod());
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/api/v1/entitlements/ent-redrive")) {
                body = """
                    {"entitlementId":"ent-redrive","segmentBookingId":"sb-redrive","journeyOrderId":"ord-redrive",
                     "travelerRef":"tvl-redrive","segmentRef":"seg-redrive","status":"VOIDED","voidedAt":"2026-09-25T08:00:03Z"}
                    """;
            } else if (path.equals("/api/v1/internal/segment-bookings/sb-redrive")) {
                body = """
                    {"segmentBookingId":"sb-redrive","journeyOrderId":"ord-redrive","travelerRef":"tvl-redrive",
                     "segmentRef":"seg-redrive","status":"%s","capacityHoldId":"hold-redrive","entitlementId":"ent-redrive"}
                    """.formatted(bookingStatus);
            } else if (path.equals("/api/v1/capacity-holds/hold-redrive")) {
                status = holdStatusCode;
                body = status == 404 ? "{\"code\":\"NOT_FOUND\"}" : """
                    {"holdId":"hold-redrive","segmentRef":"seg-redrive","segmentBookingRef":"sb-redrive","status":"%s"}
                    """.formatted(holdState);
            } else if (path.equals("/api/v1/refunds")) {
                body = """
                    {"total":1,"items":[{"refundId":"ref-redrive","paymentIntentId":"pi-redrive",
                     "businessCaseRef":"psc-%s","status":"SETTLED","amount":{"currency":"CNY","minorUnits":%d}}]}
                    """.formatted(source.caseId(), refundMinorUnits);
            } else {
                status = 500;
                body = "{\"error\":\"unexpected request\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
