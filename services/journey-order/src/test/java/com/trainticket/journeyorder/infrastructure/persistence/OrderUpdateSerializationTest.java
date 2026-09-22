package com.trainticket.journeyorder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OrderUpdateSerializationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_RISK_ORDER_SNAPSHOT", matches=".+")
    void concurrentRiskAndTravelerUpdatesPreserveEveryFactAndReadersRemainResponsive() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var sample = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_RISK_ORDER_SNAPSHOT"))));
        String orderId = sample.path("id").asText();
        String travelerId = sample.path("data").path("travelers").get(0).path("travelerId").asText();
        String verificationRef = sample.path("data").path("travelers").get(0).path("eligibilityRef").path("eligibilityId").asText();
        assertFalse(verificationRef.isBlank());
        String schema = "order_serial_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        try (var admin = new HikariDataSource(config)) {
            var adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setMaximumPoolSize(12);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var dataSource = new HikariDataSource(config); var workers = Executors.newFixedThreadPool(8)) {
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.execute(Files.readString(Path.of("migrations/001_journey_order_persistence.sql")));
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)", orderId, sample.path("version").asLong(), sample.path("data").toString());
                var repository = new PostgresJourneyOrderStateRepository(dataSource, mapper);
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                var lockHeld = new CountDownLatch(1);
                var releaseLock = new CountDownLatch(1);
                var blocker = workers.submit(() -> transaction.execute(status -> {
                    repository.findOrderForUpdate(orderId).orElseThrow();
                    lockHeld.countDown();
                    try { assertTrue(releaseLock.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                    return null;
                }));
                assertTrue(lockHeld.await(5, TimeUnit.SECONDS));
                try {
                    var reader = workers.submit(() -> repository.findOrder(orderId).orElseThrow());
                    assertEquals(orderId, reader.get(1, TimeUnit.SECONDS).order().orderId());
                    var updatingReader = workers.submit(() -> transaction.execute(status -> repository.findOrdersForTravelerUpdates(travelerId)));
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> updatingReader.get(100, TimeUnit.MILLISECONDS));
                    releaseLock.countDown();
                    assertEquals(1, updatingReader.get(5, TimeUnit.SECONDS).size());
                } finally { releaseLock.countDown(); }
                blocker.get(5, TimeUnit.SECONDS);
                int initialFacts = repository.findOrder(orderId).orElseThrow().order().timeline().size();
                var start = new CountDownLatch(1);
                var results = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 16; index++) {
                    int update = index;
                    results.add(workers.submit(() -> {
                        start.await();
                        return transaction.execute(status -> {
                            var stored = update % 2 == 0 ? repository.findOrderForUpdate(orderId).orElseThrow()
                                : repository.findOrdersForTravelerUpdates(travelerId).getFirst();
                            if (update % 2 == 0) stored.order().recordRiskAssessmentAllowed(Instant.now(), "cmd-concurrent-risk", "evt-risk", "corr-risk");
                            else stored.order().recordTravelerVerificationStatus(travelerId, "PASSED", verificationRef, Instant.now());
                            repository.saveOrder(stored.order(), stored.idempotencyKey());
                            return null;
                        });
                    }));
                }
                start.countDown();
                for (var result : results) result.get(10, TimeUnit.SECONDS);
                var finalOrder = repository.findOrder(orderId).orElseThrow().order();
                assertEquals(sample.path("version").asLong() + 16, finalOrder.version());
                assertEquals(initialFacts + 16, finalOrder.timeline().size());
                assertTrue(finalOrder.confirmationConditions().riskClear());
                String eventId = "evt-" + com.trainticket.platformkit.idempotency.UuidV7.generate();
                results.clear();
                for (int index = 0; index < 8; index++) {
                    results.add(workers.submit(() -> transaction.execute(status -> {
                        repository.lockEvent(eventId);
                        if (repository.isEventProcessed(eventId)) return null;
                        var stored = repository.findOrderForUpdate(orderId).orElseThrow();
                        stored.order().recordRiskAssessmentAllowed(Instant.now(), "cmd-risk", eventId, "corr-risk");
                        repository.saveOrder(stored.order(), stored.idempotencyKey());
                        repository.recordProcessedEvent(eventId, "risk-compliance");
                        return null;
                    })));
                }
                for (var result : results) result.get(10, TimeUnit.SECONDS);
                assertEquals(finalOrder.version() + 1, repository.findOrder(orderId).orElseThrow().order().version());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
            } finally { adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
