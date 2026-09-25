package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.postsales.domain.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CompensationExecutionRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-25T04:00:00Z");

    @Test
    void recoversAnAlreadyIssuedWalletCreditWithoutPostingAnotherCredit() throws Exception {
        Fixture fixture = fixture();
        fixture.caseAggregate.recordCompensationOperation("request", request(fixture.caseId));
        fixture.repository.save(fixture.caseAggregate);
        AtomicInteger postCount = new AtomicInteger();
        try (StubServer server = new StubServer(fixture, false, postCount)) {
            CompensationExecutionService execution = execution(fixture, new InMemoryCompensationMismatchStore(), server.base());

            PostSalesCase recovered = execution.advance(fixture.caseId, "corr-recover");

            assertEquals("ben-existing", recovered.compensationOperations().get("issued").get("benefitId"));
            assertEquals(0, postCount.get(), "recovery must discover the existing wallet credit, not issue another one");
            assertEquals(PostSalesCaseStatus.APPLIED, advanceToApplied(execution, fixture.caseId).status());
            assertEquals(0, postCount.get(), "subsequent replay must remain read-only for wallet and payment");
            server.assertNoUnexpectedRequests();
        }
    }

    private static PostSalesCase advanceToApplied(CompensationExecutionService execution, String caseId) {
        PostSalesCase current = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            current = execution.advance(caseId, "corr-recover");
            if (current.status() == PostSalesCaseStatus.APPLIED) return current;
        }
        return current;
    }

    private static CompensationExecutionService execution(Fixture fixture, CompensationMismatchStore mismatchStore, String base) {
        PostSalesApplicationService cases = new PostSalesApplicationService(
            fixture.repository, ignored -> {}, ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new CompensationExecutionService(cases, new ChangeExecutionGuard(), RestClient.builder(),
            fixture.mapper, mismatchStore, Clock.fixed(NOW, ZoneOffset.UTC), base);
    }

    static Fixture fixture() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        PostSalesCase source = PostSalesCase.open("ord-comp-1", PostSalesCaseType.COMPENSATION,
            PostSalesScope.ticket("sb-comp-1", "seg-comp-1", "tvl-comp-1", "ent-comp-1"),
            "SERVICE_QUALITY_COMPLAINT", "acc-1", "idem-comp-1", NOW, "cmd-open", "corr-open");
        source.beginEvaluation(NOW, "cmd-evaluate", "cmd-open", "corr-open");
        source.recordDecision(new PostSalesDecision(source.caseId(), 1, DecisionKind.COMPENSATION, true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-comp-1", "rules-comp-1", "v1", NOW, Map.of("journeyOrderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(Money.zero("CNY"), Money.of("10.00", "CNY"), "approved complaint compensation"),
            null, null, null, NOW, NOW.plusSeconds(900)), false, false, NOW, "cmd-quote", "cmd-evaluate", "corr-open");
        source.approve("approval-comp-1", NOW.plusSeconds(1), "cmd-approve", "cmd-quote", "corr-open");
        source.requestExecution(NOW.plusSeconds(2), "cmd-execute", "cmd-approve", "corr-open");
        repository.save(source);
        return new Fixture(mapper, repository, source, source.caseId());
    }

    static Map<String,Object> request(String caseId) {
        return Map.ofEntries(
            Map.entry("accountId", "acc-1"), Map.entry("benefitType", "COMPENSATION_CREDIT"),
            Map.entry("balanceType", "PROMOTION_CREDIT"), Map.entry("amount", Map.of("currency", "CNY", "minorUnits", 1000)),
            Map.entry("issuanceSource", "POST_SALES_COMP"), Map.entry("caseId", "psc-" + caseId),
            Map.entry("applicableScope", Map.of("scopeType", "ANY_TRIP", "currency", "CNY")),
            Map.entry("redemptionRule", Map.of("singleUse", false, "requiresReservation", false)),
            Map.entry("revocationRule", Map.of()), Map.entry("validFrom", NOW.toString()),
            Map.entry("validUntil", NOW.plusSeconds(30L * 86400L).toString()),
            Map.entry("businessReason", Map.of("reasonType", "POST_SALES_COMP", "reasonCode", "SERVICE_QUALITY_COMPLAINT", "referenceType", "POST_SALES_CASE", "referenceId", "psc-" + caseId)));
    }

    record Fixture(ObjectMapper mapper, InMemoryPostSalesRepository repository, PostSalesCase caseAggregate, String caseId) {}

    static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final Fixture fixture;
        private final boolean refundConflict;
        private final AtomicInteger postCount;
        private final AtomicInteger unexpected = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();

        StubServer(Fixture fixture, boolean refundConflict, AtomicInteger postCount) throws Exception {
            this.fixture = fixture; this.refundConflict = refundConflict; this.postCount = postCount;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            requests.incrementAndGet();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method)) postCount.incrementAndGet();
            String body;
            int status = 200;
            if (path.equals("/api/v1/journey-orders/ord-comp-1")) {
                body = "{\"orderId\":\"ord-comp-1\",\"accountId\":\"acc-1\",\"status\":\"CONFIRMED\"}";
            } else if (path.equals("/api/v1/entitlements/ent-comp-1")) {
                body = "{\"entitlementId\":\"ent-comp-1\",\"journeyOrderId\":\"ord-comp-1\",\"status\":\"ISSUED\"}";
            } else if (path.equals("/api/v1/refunds")) {
                body = refundConflict
                    ? "{\"total\":1,\"items\":[{\"refundId\":\"ref-settled\",\"status\":\"SETTLED\",\"amount\":{\"currency\":\"CNY\",\"minorUnits\":1000}}]}"
                    : "{\"total\":0,\"items\":[]}";
            } else if (path.equals("/api/v1/payment-intents/by-business/ord-comp-1")) {
                body = "{\"paymentIntentId\":\"pi-1\",\"businessRef\":\"ord-comp-1\",\"status\":\"CAPTURED\",\"capturedAmount\":{\"currency\":\"CNY\",\"minorUnits\":10000},\"refundedAmount\":{\"currency\":\"CNY\",\"minorUnits\":0}}";
            } else if (path.equals("/api/v1/internal/benefits/by-case/psc-" + fixture.caseId)
                    || path.equals("/api/v1/benefits/ben-existing")) {
                body = "{\"benefitId\":\"ben-existing\",\"accountId\":\"acc-1\",\"benefitType\":\"COMPENSATION_CREDIT\",\"balanceType\":\"PROMOTION_CREDIT\",\"issuedAmount\":{\"currency\":\"CNY\",\"minorUnits\":1000},\"issuanceSource\":\"POST_SALES_COMP\",\"caseId\":\"psc-" + fixture.caseId + "\"}";
            } else {
                unexpected.incrementAndGet(); status = 500; body = "{\"error\":\"unexpected request\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        void assertNoUnexpectedRequests() { assertEquals(0, unexpected.get()); }
        int requestCount() { return requests.get(); }
        @Override public void close() { server.stop(0); }
    }
}
