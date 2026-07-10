package com.trainticket.platformkit.persistence;

import com.trainticket.platformkit.messaging.RedisStreamOperations;
import com.trainticket.platformkit.messaging.RedisStreamOperations.StreamMessage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class OutboxRelay implements AutoCloseable {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(
        Long.parseLong(System.getenv().getOrDefault("OUTBOX_POLL_INTERVAL_MS", "50")));

    private final JdbcOperations jdbc;
    private final RedisStreamOperations streams;
    private final Duration pollInterval;
    private volatile boolean dependencyReady = true;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public OutboxRelay(javax.sql.DataSource dataSource, RedisStreamOperations streams) {
        this(new JdbcTemplate(dataSource), streams, DEFAULT_POLL_INTERVAL);
    }

    public OutboxRelay(JdbcOperations jdbc, RedisStreamOperations streams, Duration pollInterval) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.streams = Objects.requireNonNull(streams, "streams are required");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval is required");
        if (pollInterval.toMillis() > 500) {
            throw new IllegalArgumentException("outbox relay poll interval must be <= 500ms");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::safePollOnce, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public int pollOnce() {
        List<OutboxRow> rows = jdbc.query(
            "SELECT seq, stream, envelope::text AS envelope FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT 100",
            (rs, rowNum) -> new OutboxRow(rs.getLong("seq"), rs.getString("stream"), rs.getString("envelope"))
        );
        if (rows.isEmpty()) {
            return 0;
        }

        streams.publishBatch(rows.stream()
            .map(row -> new StreamMessage(row.stream(), row.envelope()))
            .toList());
        dependencyReady = true;

        String placeholders = String.join(", ", rows.stream().map(row -> "?").toList());
        Object[] seqs = rows.stream().map(OutboxRow::seq).toArray();
        jdbc.update("UPDATE outbox SET published_at = now() WHERE seq IN (" + placeholders + ") AND published_at IS NULL", seqs);
        return rows.size();
    }

    private void safePollOnce() {
        try {
            pollOnce();
        } catch (RuntimeException ignored) {
            dependencyReady = false;
            // Readiness captures dependencies; relay retries on the next tick for at-least-once delivery.
        }
    }

    public boolean isDependencyReady() {
        return dependencyReady;
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
    }

    private record OutboxRow(long seq, String stream, String envelope) {
    }
}
