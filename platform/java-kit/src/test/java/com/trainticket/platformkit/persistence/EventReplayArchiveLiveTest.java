package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class EventReplayArchiveLiveTest {
    @Test
    void archiveSurvivesOutboxCleanupAndRejectsIdentityDrift() throws Exception {
        String schema = "event_replay_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setConnectionInitSql("SET search_path TO " + schema);
        try (var pool = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE TABLE outbox(seq bigserial PRIMARY KEY,event_id text UNIQUE NOT NULL,stream text NOT NULL,envelope jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz)");
            jdbc.execute("CREATE TABLE event_replay_archive(archive_seq bigserial PRIMARY KEY,event_id text UNIQUE NOT NULL,event_type text NOT NULL,stream text NOT NULL,envelope jsonb NOT NULL,archived_at timestamptz NOT NULL DEFAULT now(),retained_until timestamptz NOT NULL)");
            jdbc.execute("CREATE TABLE event_replay_retention_state(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),discarded_through_seq bigint NOT NULL,updated_at timestamptz NOT NULL DEFAULT now())");
            OutboxAppender appender = new OutboxAppender(pool, new ObjectMapper().findAndRegisterModules(), Duration.ofDays(7), Set.of("RefundSettled"));
            EventEnvelope event = event(Map.of("refundId", "ref-1"));

            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
                appender.append(event);
                throw new RuntimeException("fault after atomic append");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM event_replay_archive", Integer.class));

            transaction.executeWithoutResult(status -> appender.append(event));
            transaction.executeWithoutResult(status -> appender.append(event));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_replay_archive", Integer.class));
            jdbc.update("DELETE FROM outbox");

            EventReplayArchive archive = new EventReplayArchive(pool);
            EventReplayArchive.ReplayPage page = archive.readAfter(0, 100);
            assertEquals(1, page.events().size());
            assertEquals(event.eventId(), page.events().getFirst().eventId());

            assertThrows(IllegalStateException.class, () -> appender.append(event(Map.of("refundId", "ref-tampered"))));
            EventEnvelope second = new EventEnvelope(
                "evt-0199a000-0000-7000-8000-000000000014", "RefundSettled",
                Instant.parse("2026-09-25T04:01:00Z"),
                "corr-0199a000-0000-7000-8000-000000000015",
                "cmd-0199a000-0000-7000-8000-000000000016", "payment", 1,
                Map.of("refundId", "ref-2"), null, null);
            appender.append(second);
            jdbc.update("UPDATE event_replay_archive SET retained_until=now()-interval '1 second'");
            assertEquals(2, archive.deleteExpired(100));
            assertThrows(ReplayRetentionGapException.class, () -> archive.readAfter(1, 100));
        } finally {
            try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope(
            "evt-0199a000-0000-7000-8000-000000000011", "RefundSettled",
            Instant.parse("2026-09-25T04:00:00Z"),
            "corr-0199a000-0000-7000-8000-000000000012",
            "cmd-0199a000-0000-7000-8000-000000000013", "payment", 1, payload, null, null);
    }
}
