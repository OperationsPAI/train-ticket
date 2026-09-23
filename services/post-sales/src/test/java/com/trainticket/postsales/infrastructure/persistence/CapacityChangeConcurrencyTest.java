package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.domain.*;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
class CapacityChangeConcurrencyTest {
    @Test
    void changeCompletionRequiresEveryReleaseAndRetainsConcurrentOperationResults() throws Exception {
        withDatabase((repository, jdbc, transaction) -> {
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            var source = change();
            Instant now = Instant.now();
            source.selectChangeOffer("offer-target");
            source.beginEvaluation(now, "cmd-evaluate", "cmd", "corr");
            var assessment = new ChangePolicyEngine().evaluateChange(Money.of("100", "CNY"), Money.of("120", "CNY"), now, now.plusSeconds(864000), 0);
            source.recordDecision(PostSalesDecision.change(source.caseId(), true, "ELIGIBLE",
                new RuleEvaluationSnapshot("quote", "rules", "1", now, Map.of("orderId", source.journeyOrderId())),
                AmountDecisionSnapshot.extraCharge(assessment.changeFee(), assessment.netPayable(), assessment.explanation()),
                new ChangeFlowSnapshot("offer-target", "hold-target", ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT, "void", "issue", "release"),
                assessment, now, now.plusSeconds(600)), false, false, now, "cmd-quote", "cmd-evaluate", "corr");
            source.approve("approval", now, "cmd-approve", "cmd-quote", "corr");
            source.startExecution(now, "cmd-execute", "cmd-approve", "corr");
            transaction.executeWithoutResult(status -> repository.save(source));
            var service = new PostSalesApplicationService(repository, new OutboxAppender(jdbc.getDataSource(), mapper)::append,
                new FarePricingAdjustmentQuoteClient("http://localhost:18001", "http://localhost:18001", "http://localhost:18001", RestClient.builder()),
                new PostgresPostSalesPolicyContextStore(jdbc.getDataSource(), mapper),
                new PostgresPostSalesExternalEventProjectionStore(jdbc.getDataSource(), mapper), Clock.systemUTC());
            assertThrows(DomainRuleViolation.class, () -> transaction.execute(status -> service.failUnpaidChange(source.caseId(), "corr")));
            transaction.executeWithoutResult(status -> service.recordChangeOperation(source.caseId(), "order-applied", Map.of("orderId", source.journeyOrderId())));
            assertThrows(DomainRuleViolation.class, () -> transaction.execute(status -> service.completeChange(source.caseId(), "corr")));
            transaction.executeWithoutResult(status -> service.recordChangeCapacityReleased(source.caseId(), "booking-1"));
            assertThrows(DomainRuleViolation.class, () -> transaction.execute(status -> service.completeChange(source.caseId(), "corr")));
            transaction.executeWithoutResult(status -> service.applyForSegmentBooking("booking-2", "evt-capacity", "corr"));
            transaction.executeWithoutResult(status -> service.completeChange(source.caseId(), "corr"));
            transaction.executeWithoutResult(status -> service.completeChange(source.caseId(), "corr"));
            transaction.executeWithoutResult(status -> service.applyForSegmentBooking("booking-2", "evt-capacity", "corr"));
            transaction.executeWithoutResult(status -> service.recordChangeCapacityReleased(source.caseId(), "booking-1"));
            var saved = repository.findById(source.caseId()).orElseThrow();
            assertEquals(PostSalesCaseStatus.APPLIED, saved.status());
            assertEquals(Set.of("booking-1", "booking-2"), saved.releasedOrderItems());
            assertEquals(Map.of("orderId", source.journeyOrderId()), saved.changeOperations().get("order-applied"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'PostSalesApplied'", Integer.class));
        });
    }

    @Test
    void releaseWaitsForOrderLockAndReadsCommittedChangeOperations() throws Exception {
        withDatabase((repository, jdbc, transaction) -> {
            var source = change();
            transaction.executeWithoutResult(status -> repository.save(source));
            var readerPid = new CompletableFuture<Integer>();
            try (var workers = Executors.newSingleThreadExecutor()) {
                var reader = new CompletableFuture<Future<?>>();
                transaction.executeWithoutResult(status -> {
                    repository.lockOrder(source.journeyOrderId());
                    reader.complete(workers.submit(() -> transaction.executeWithoutResult(readStatus -> {
                        readerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        var current = repository.findByOrderItemRef("booking-1").getFirst();
                        assertEquals(Map.of("confirmed", true), current.changeOperations().get("booking-confirmed"));
                        current.recordCapacityReleased("booking-1");
                        repository.save(current);
                    })));
                    try {
                        int pid = readerPid.get(5, TimeUnit.SECONDS);
                        String waitEvent = null;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (System.nanoTime() < deadline) {
                            waitEvent = jdbc.queryForObject("SELECT (SELECT wait_event FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')", String.class, pid);
                            if (waitEvent != null) break;
                            Thread.sleep(10);
                        }
                        assertEquals("advisory", waitEvent);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    var current = repository.findById(source.caseId()).orElseThrow();
                    current.recordChangeOperation("booking-confirmed", Map.of("confirmed", true));
                    repository.save(current);
                });
                reader.get(5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            }
            var saved = repository.findById(source.caseId()).orElseThrow();
            assertEquals(Set.of("booking-1"), saved.releasedOrderItems());
            assertEquals(Map.of("confirmed", true), saved.changeOperations().get("booking-confirmed"));
        });
    }

    @Test
    void concurrentReleaseReplaysAndChangeWritesPreserveEveryResult() throws Exception {
        withDatabase((repository, jdbc, transaction) -> {
            var source = change();
            transaction.executeWithoutResult(status -> repository.save(source));
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(8)) {
                var results = new ArrayList<Future<?>>();
                for (int index = 0; index < 32; index++) {
                    int operation = index;
                    results.add(workers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        transaction.executeWithoutResult(status -> {
                            if (operation % 2 == 0) {
                                String booking = "booking-" + (operation % 4 / 2 + 1);
                                var current = repository.findByOrderItemRef(booking).getFirst();
                                current.recordCapacityReleased(booking);
                                repository.save(current);
                            } else {
                                repository.lockOrder(source.journeyOrderId());
                                var current = repository.findById(source.caseId()).orElseThrow();
                                current.recordChangeOperation("operation-" + operation, Map.of("completed", true));
                                repository.save(current);
                            }
                        });
                        return null;
                    }));
                }
                start.countDown();
                for (var result : results) result.get(15, TimeUnit.SECONDS);
            }
            var saved = repository.findById(source.caseId()).orElseThrow();
            assertEquals(Set.of("booking-1", "booking-2"), saved.releasedOrderItems());
            assertEquals(16, saved.changeOperations().size());
            for (int index = 1; index < 32; index += 2) {
                assertEquals(Map.of("completed", true), saved.changeOperations().get("operation-" + index));
            }
        });
    }

    private static PostSalesCase change() {
        return PostSalesCase.open("order-" + UUID.randomUUID(), PostSalesCaseType.CHANGE,
            new PostSalesScope(List.of("booking-1", "booking-2"), List.of("segment"),
                List.of("traveler-1", "traveler-2"), List.of("ticket-1", "ticket-2")),
            "CUSTOMER_REQUEST", "account", UUID.randomUUID().toString(), Instant.now(), "cmd", "corr");
    }

    private static void withDatabase(DatabaseCheck check) throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "capacity_change_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = new HikariDataSource(config)) {
            var adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setMaximumPoolSize(10);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var dataSource = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(dataSource);
                try (var migrations = Files.list(Path.of("migrations"))) {
                    for (var file : migrations.sorted().toList()) jdbc.execute(Files.readString(file));
                }
                var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                check.run(new PostgresPostSalesRepository(dataSource, mapper), jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            } finally {
                adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private interface DatabaseCheck {
        void run(PostgresPostSalesRepository repository, JdbcTemplate jdbc, TransactionTemplate transaction) throws Exception;
    }
}
