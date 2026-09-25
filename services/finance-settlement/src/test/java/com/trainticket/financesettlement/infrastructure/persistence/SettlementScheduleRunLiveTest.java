package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.application.SettlementScheduleRunRepository;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SettlementScheduleRunLiveTest {
    @Test
    void oneReplicaOwnsJobAndCompletedResultSurvivesRepositoryRestart() throws Exception {
        String schema = "finance_schedule_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(20);
        config.setConnectionInitSql("SET search_path TO " + schema);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                new MigrationRunner(pool, new DataSourceTransactionManager(pool)).run(Path.of("migrations"));
                SettlementScheduleRunRepository repository = new PostgresSettlementScheduleRunRepository(pool,
                    new ObjectMapper().registerModule(new JavaTimeModule()));
                Instant now = Instant.parse("2026-09-25T06:00:00Z");
                LocalDate day = LocalDate.parse("2026-09-24");
                String key = "daily-reconciliation:" + day;
                try (var executor = Executors.newFixedThreadPool(16)) {
                    List<Callable<Boolean>> contenders = java.util.stream.IntStream.range(0, 16)
                        .mapToObj(index -> (Callable<Boolean>) () -> repository.acquire(key, "DAILY_RECONCILIATION", day, day,
                            "node-" + index, now, Duration.ofMinutes(10)).isPresent()).toList();
                    long acquired = executor.invokeAll(contenders).stream().filter(future -> {
                        try { return future.get(); }
                        catch (Exception error) { throw new RuntimeException(error); }
                    }).count();
                    assertEquals(1, acquired);
                }
                var running = repository.findByKey(key).orElseThrow();
                repository.complete(key, running.leaseOwner(), List.of("rb-1", "ss-1"), now.plusSeconds(1));

                SettlementScheduleRunRepository restarted = new PostgresSettlementScheduleRunRepository(pool,
                    new ObjectMapper().registerModule(new JavaTimeModule()));
                var completed = restarted.findByKey(key).orElseThrow();
                assertEquals("SUCCEEDED", completed.status());
                assertEquals(List.of("rb-1", "ss-1"), completed.resultRefs());
                assertFalse(restarted.acquire(key, "DAILY_RECONCILIATION", day, day, "node-late", now.plus(Duration.ofHours(1)), Duration.ofMinutes(10)).isPresent());

                String retryKey = "supplier-settlement:DAILY:" + day + ":" + day;
                var first = restarted.acquire(retryKey, "SUPPLIER_SETTLEMENT", day, day, "node-a", now, Duration.ofSeconds(30)).orElseThrow();
                assertTrue(first.leaseUntil().isAfter(now));
                assertTrue(restarted.acquire(retryKey, "SUPPLIER_SETTLEMENT", day, day, "node-b", now.plusSeconds(31), Duration.ofMinutes(1)).isPresent());
            } finally {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
