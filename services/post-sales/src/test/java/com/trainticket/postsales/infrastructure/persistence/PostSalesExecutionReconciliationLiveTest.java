package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.application.InMemoryPostSalesPolicyContextStore;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.application.PostSalesExecutionEvidenceReader;
import com.trainticket.postsales.application.PostSalesMapper;
import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RefundPolicyEngine;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostSalesExecutionReconciliationLiveTest {
    @Test
    void concurrentReconciliationConvergesOnceWithoutCallingDownstreamWrites() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "execution_reconcile_" + UUID.randomUUID().toString().replace("-", "");
        try (HikariDataSource admin = new HikariDataSource(config)) {
            JdbcTemplate adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setMaximumPoolSize(16);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                try (var migrations = Files.list(Path.of("migrations"))) {
                    for (Path migration : migrations.sorted().toList()) jdbc.execute(Files.readString(migration));
                }
                ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                PostgresPostSalesRepository repository = new PostgresPostSalesRepository(dataSource, mapper);
                TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                PostSalesCase source = executingZeroRefund();
                transaction.executeWithoutResult(status -> repository.save(source));
                long initialVersion = source.version();
                Clock clock = Clock.fixed(Instant.parse("2026-09-25T08:10:00Z"), ZoneOffset.UTC);
                PostSalesApplicationService cases = new PostSalesApplicationService(repository,
                    new OutboxAppender(dataSource, mapper)::append,
                    new FarePricingAdjustmentQuoteClient("http://localhost:1", "http://localhost:1", "http://localhost:1", RestClient.builder()),
                    new InMemoryPostSalesPolicyContextStore(), clock);

                try (FactServer facts = new FactServer(source)) {
                    PostSalesExecutionEvidenceReader reader = new PostSalesExecutionEvidenceReader(
                        cases, mapper, RestClient.builder(), facts.base(), facts.base(), facts.base(), facts.base());
                    int callers = 12;
                    CountDownLatch start = new CountDownLatch(1);
                    try (var workers = Executors.newFixedThreadPool(callers)) {
                        List<Future<PostSalesCaseStatus>> results = new ArrayList<>();
                        for (int index = 0; index < callers; index++) {
                            int caller = index;
                            results.add(workers.submit(() -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                var evidence = reader.read(source.caseId(), "corr-reconcile-" + caller);
                                return transaction.execute(status -> cases.reconcileExecution(evidence,
                                    "cmd-reconcile-" + caller, "corr-reconcile-" + caller).status());
                            }));
                        }
                        start.countDown();
                        for (Future<PostSalesCaseStatus> result : results) {
                            assertEquals(PostSalesCaseStatus.APPLIED, result.get(15, TimeUnit.SECONDS));
                        }
                    }
                    assertEquals(0, facts.writeRequests.get(), "reconciliation must never invoke a downstream write");
                }

                PostSalesCase saved = repository.findById(source.caseId()).orElseThrow();
                assertEquals(PostSalesCaseStatus.APPLIED, saved.status());
                assertEquals(initialVersion + 1, saved.version());
                assertEquals(java.util.Set.of("sb-redrive"), saved.releasedOrderItems());
                assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PostSalesApplied'", Integer.class));

                jdbc.update("DELETE FROM outbox");
                assertEquals(PostSalesCaseStatus.APPLIED,
                    transaction.execute(status -> cases.reconcileExecution(
                        new com.trainticket.postsales.domain.PostSalesExecutionEvidence(
                            source.caseId(), source.journeyOrderId(), List.of(), List.of(), List.of(), List.of()),
                        "cmd-applied", "corr-applied").status()));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            } finally {
                adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static PostSalesCase executingZeroRefund() {
        Instant now = Instant.parse("2026-09-25T08:00:00Z");
        PostSalesCase source = PostSalesCase.open("ord-redrive", PostSalesCaseType.REFUND,
            PostSalesScope.ticket("sb-redrive", "seg-redrive", "tvl-redrive", "ent-redrive"),
            "CUSTOMER_REQUEST", "acct-redrive", "idem-redrive-live", now, "cmd-open", "corr-open");
        source.beginEvaluation(now, "cmd-evaluate", "cmd-evaluate", "corr-evaluate");
        Money zero = Money.zero("CNY");
        var assessment = new RefundPolicyEngine().evaluateRefund(
            zero, now, now.plusSeconds(10 * 86_400), false, "ADULT", 1);
        source.recordDecision(PostSalesDecision.refund(source.caseId(), true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-redrive", "rules-redrive", "1", now,
                Map.of("orderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(),
                assessment.explanation(), assessment.componentDecisions()),
            assessment, now, now.plusSeconds(600)), false, false, now,
            "cmd-quote", "cmd-evaluate", "corr-evaluate");
        source.approve("http-approval", now.plusSeconds(1), "cmd-approve", "cmd-approve", "corr-approve");
        source.startExecution(now.plusSeconds(2), "cmd-execute", "cmd-approve", "corr-approve");
        return source;
    }

    private static final class FactServer implements AutoCloseable {
        private final HttpServer server;
        private final PostSalesCase source;
        private final AtomicInteger writeRequests = new AtomicInteger();

        FactServer(PostSalesCase source) throws Exception {
            this.source = source;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            if (!"GET".equals(exchange.getRequestMethod())) writeRequests.incrementAndGet();
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
                     "segmentRef":"seg-redrive","status":"CANCELLED","capacityHoldId":"hold-redrive","entitlementId":"ent-redrive"}
                    """;
            } else if (path.equals("/api/v1/capacity-holds/hold-redrive")) {
                status = 404;
                body = "{\"code\":\"NOT_FOUND\"}";
            } else if (path.equals("/api/v1/refunds")) {
                body = "{\"total\":0,\"items\":[]}";
            } else {
                status = 500;
                body = "{\"error\":\"unexpected request for " + source.caseId() + "\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
