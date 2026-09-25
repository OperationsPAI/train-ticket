package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.application.PostSalesApplicationService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostSalesExecutionRedriveConcurrencyTest {
    @Test
    void concurrentHistoricalRedrivesPublishOneStablePairAndAppliedDoesNotRepublish() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "execution_redrive_" + UUID.randomUUID().toString().replace("-", "");
        try (HikariDataSource admin = new HikariDataSource(config)) {
            JdbcTemplate adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setMaximumPoolSize(16);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                try (var migrations = Files.list(Path.of("migrations"))) {
                    for (Path migration : migrations.sorted().toList()) {
                        jdbc.execute(Files.readString(migration));
                    }
                }
                ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                PostgresPostSalesRepository repository = new PostgresPostSalesRepository(dataSource, mapper);
                TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                var source = executingRefund();
                transaction.executeWithoutResult(status -> repository.save(source));
                long initialVersion = source.version();
                PostSalesApplicationService service = new PostSalesApplicationService(repository,
                    new OutboxAppender(dataSource, mapper)::append,
                    new FarePricingAdjustmentQuoteClient("http://localhost:1", "http://localhost:1", "http://localhost:1", RestClient.builder()),
                    new PostgresPostSalesPolicyContextStore(dataSource, mapper),
                    new PostgresPostSalesExternalEventProjectionStore(dataSource, mapper),
                    Clock.fixed(Instant.parse("2026-09-25T08:00:10Z"), ZoneOffset.UTC));

                int callers = 12;
                CountDownLatch start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(callers)) {
                    List<Future<PostSalesCaseStatus>> results = new ArrayList<>();
                    for (int index = 0; index < callers; index++) {
                        int caller = index;
                        results.add(workers.submit(() -> {
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            return transaction.execute(status -> service.approve(source.caseId(),
                                "cmd-redrive-" + caller, "corr-redrive-" + caller).status());
                        }));
                    }
                    start.countDown();
                    for (Future<PostSalesCaseStatus> result : results) {
                        assertEquals(PostSalesCaseStatus.EXECUTING, result.get(15, TimeUnit.SECONDS));
                    }
                }

                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
                assertEquals(List.of("PostSalesApproved", "PostSalesExecutionStarted"), jdbc.queryForList(
                    "SELECT envelope->>'eventType' FROM outbox ORDER BY seq", String.class));
                assertEquals(initialVersion + 1, repository.findById(source.caseId()).orElseThrow().version());

                transaction.executeWithoutResult(status -> {
                    repository.lockOrder(source.journeyOrderId());
                    var applied = repository.findById(source.caseId()).orElseThrow();
                    applied.applyResult("already completed", Instant.parse("2026-09-25T08:01:00Z"),
                        "cmd-apply", "cmd-apply", "corr-apply");
                    repository.save(applied);
                });
                jdbc.update("DELETE FROM outbox");

                transaction.execute(status -> service.approve(source.caseId(), "cmd-after-applied", "corr-after-applied"));

                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            } finally {
                adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static PostSalesCase executingRefund() {
        Instant now = Instant.parse("2026-09-25T08:00:00Z");
        PostSalesCase source = PostSalesCase.open("ord-redrive", PostSalesCaseType.REFUND,
            PostSalesScope.ticket("sb-redrive", "seg-redrive", "tvl-redrive", "ent-redrive"),
            "CUSTOMER_REQUEST", "acct-redrive", "idem-redrive", now, "cmd-open", "corr-open");
        source.beginEvaluation(now, "cmd-evaluate", "cmd-evaluate", "corr-evaluate");
        Money fare = Money.of("100.00", "CNY");
        var assessment = new RefundPolicyEngine().evaluateRefund(
            fare, now, now.plusSeconds(10 * 86_400), false, "ADULT", 1);
        source.recordDecision(PostSalesDecision.refund(source.caseId(), true, "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-redrive", "rules-redrive", "1", now,
                Map.of("orderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(),
                assessment.explanation(), assessment.componentDecisions()),
            assessment, now, now.plusSeconds(600)), false, false, now,
            "cmd-quote", "cmd-evaluate", "corr-evaluate");
        source.assignRefundPayments(List.of(Map.of(
            "paymentIntentId", "pi-redrive",
            "amount", PostSalesMapper.money(source.decision().amountSnapshot().refundAmount()))));
        source.approve("http-approval", now.plusSeconds(1), "cmd-approve", "cmd-approve", "corr-approve");
        source.startExecution(now.plusSeconds(2), "cmd-execute", "cmd-approve", "corr-approve");
        return source;
    }
}
