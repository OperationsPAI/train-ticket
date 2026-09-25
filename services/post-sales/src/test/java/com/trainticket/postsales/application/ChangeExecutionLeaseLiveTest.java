package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ChangeExecutionLeaseLiveTest {
    @Test
    void separateReplicasKeepOneCaseSingleFlightAndRenewItsLease() throws Exception {
        withDatabase((dataSource, jdbc) -> {
            try (var firstReplica = new ChangeExecutionGuard(dataSource, Duration.ofMillis(500), Duration.ofMillis(100));
                 var secondReplica = new ChangeExecutionGuard(dataSource, Duration.ofMillis(500), Duration.ofMillis(100));
                 var workers = Executors.newSingleThreadExecutor()) {
                var acquired = new CountDownLatch(1);
                var finish = new CountDownLatch(1);
                Future<Boolean> first = workers.submit(() -> firstReplica.execute("psc-shared-case", () -> {
                    acquired.countDown();
                    try {
                        if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lease test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return true;
                }, () -> false));
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                Thread.sleep(750);
                assertFalse(secondReplica.execute("shared-case", () -> true, () -> false),
                    "another replica acquired a case whose lease heartbeat was active");
                assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM post_sales_execution_leases WHERE case_id='shared-case' AND lease_until>clock_timestamp()",
                    Integer.class));
                finish.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertTrue(secondReplica.execute("psc-shared-case", () -> true, () -> false));
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_sales_execution_leases", Integer.class));
        });
    }

    @Test
    void expiredReplicaLeaseIsTakenOver() throws Exception {
        withDatabase((dataSource, jdbc) -> {
            jdbc.update("""
                INSERT INTO post_sales_execution_leases(case_id,owner_id,lease_until)
                VALUES ('abandoned-case','dead-replica',clock_timestamp() - interval '1 second')
                """);
            try (var survivor = new ChangeExecutionGuard(dataSource, Duration.ofSeconds(2), Duration.ofMillis(250))) {
                assertTrue(survivor.execute("psc-abandoned-case", () -> true, () -> false));
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_sales_execution_leases", Integer.class));
        });
    }

    @Test
    void sixtyFourIndependentCasesAcquireWithoutAProcessWideCap() throws Exception {
        withDatabase((dataSource, jdbc) -> {
            int caseCount = 64;
            try (var guard = new ChangeExecutionGuard(dataSource, Duration.ofSeconds(5), Duration.ofSeconds(1));
                 var workers = Executors.newFixedThreadPool(caseCount)) {
                var acquired = new CountDownLatch(caseCount);
                var finish = new CountDownLatch(1);
                var calls = new ArrayList<Future<Boolean>>();
                for (int index = 0; index < caseCount; index++) {
                    String caseId = "case-" + index;
                    calls.add(workers.submit(() -> guard.execute(caseId, () -> {
                        acquired.countDown();
                        try {
                            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lease test timed out");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        return true;
                    }, () -> false)));
                }
                try {
                    assertTrue(acquired.await(5, TimeUnit.SECONDS),
                        "independent database leases were throttled; missing=" + acquired.getCount());
                    assertEquals(caseCount, jdbc.queryForObject(
                        "SELECT count(*) FROM post_sales_execution_leases WHERE lease_until>clock_timestamp()", Integer.class));
                    assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections(),
                        "execution must not hold database sessions while downstream work is in flight");
                } finally {
                    finish.countDown();
                }
                for (var call : calls) assertTrue(call.get(5, TimeUnit.SECONDS));
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_sales_execution_leases", Integer.class));
        });
    }

    private static void withDatabase(DatabaseCheck check) throws Exception {
        String schema = "change_execution_lease_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = System.getenv("TEST_DATABASE_URL");
        try (var admin = DriverManager.getConnection(databaseUrl)) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(databaseUrl);
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(1);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var dataSource = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.execute(Files.readString(Path.of("migrations/010_change_execution_leases.sql")));
                check.run(dataSource, jdbc);
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private interface DatabaseCheck {
        void run(HikariDataSource dataSource, JdbcTemplate jdbc) throws Exception;
    }
}
