package com.trainticket.platformkit.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(
        Long.parseLong(System.getenv().getOrDefault("OUTBOX_POLL_INTERVAL_MS", "50")));
    private static final int CLEANUP_EVERY_N = 20;

    private final JdbcOperations jdbc;
    private final RedisStreamOperations streams;
    private final Duration pollInterval;
    private volatile boolean dependencyReady = true;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private int pollCount;

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

    /** Rows per retention DELETE. Small enough to finish quickly on an unindexed column. */
    private static final int CLEANUP_BATCH_SIZE = 5_000;
    /** Batches per table per sweep. Bounds one pass; the next pass resumes. */
    private static final int CLEANUP_MAX_BATCHES = 20;

    private void safePollOnce() {
        try {
            pollOnce();
            if (++pollCount % CLEANUP_EVERY_N == 0) {
                cleanup();
            }
        } catch (RuntimeException ignored) {
            dependencyReady = false;
        }
    }

    /**
     * Retention sweep for the three platform tables.
     *
     * Deletes in BATCHES, and reports what it did. The previous version issued one
     * unbounded DELETE per table inside `catch (RuntimeException ignored) {}`, and
     * on a long-running cluster that combination silently stopped working
     * altogether: journey-order's processed_events had reached 1,092,388 rows and
     * 205 MB, with 1,085,529 of them past the 5-minute retention and the oldest 21
     * hours old. `processed_at` is unindexed, so the statement planned a Seq Scan
     * over the whole table and tried to delete a million rows in one transaction;
     * whatever went wrong then went into the ignored catch.
     *
     * The cost of that landed on the hot path. Every consumed event checks
     * processed_events for deduplication, so a table that grows without bound makes
     * every event slower -- journey-order was taking 151 ms per event across 4
     * threads, which is what turned its backlog into a permanent deficit rather
     * than a transient one.
     *
     * Batching keeps each statement short enough to finish and to be interrupted
     * safely, and repeats until the sweep is caught up or the batch budget is
     * spent. The budget bounds one pass; the next pass continues where this left
     * off.
     */
    private void cleanup() {
        sweep("outbox", "DELETE FROM outbox WHERE ctid IN (SELECT ctid FROM outbox "
            + "WHERE published_at IS NOT NULL AND published_at < now() - interval '30 seconds' LIMIT ?)");
        sweep("processed_events", "DELETE FROM processed_events WHERE ctid IN (SELECT ctid FROM processed_events "
            + "WHERE processed_at < now() - interval '5 minutes' LIMIT ?)");
        sweep("idempotency_records", "DELETE FROM idempotency_records WHERE ctid IN (SELECT ctid FROM idempotency_records "
            + "WHERE created_at < now() - interval '10 minutes' LIMIT ?)");
    }

    /**
     * Runs one batched sweep. Uses `ctid IN (SELECT ... LIMIT n)` because a plain
     * `DELETE ... LIMIT` is not valid in Postgres, and the subquery keeps the row
     * set bounded whether or not the retention column happens to be indexed.
     */
    private void sweep(String table, String batchedDelete) {
        long removed = 0;
        try {
            for (int batch = 0; batch < CLEANUP_MAX_BATCHES; batch++) {
                int affected = jdbc.update(batchedDelete, CLEANUP_BATCH_SIZE);
                removed += affected;
                if (affected < CLEANUP_BATCH_SIZE) {
                    break;
                }
            }
            if (removed >= (long) CLEANUP_BATCH_SIZE * CLEANUP_MAX_BATCHES) {
                // Still behind after a full budget. Worth a warning: it means the
                // table is growing faster than the sweep drains it, which is how the
                // million-row backlog accumulated in the first place.
                LOGGER.warn("retention sweep for {} removed {} rows and hit its batch budget; "
                        + "the table is still above retention. If this repeats, the retention "
                        + "column likely needs an index.", table, removed);
            } else if (removed > 0) {
                LOGGER.debug("retention sweep removed {} rows from {}", removed, table);
            }
        } catch (RuntimeException exception) {
            // Logged, not swallowed. The silent version of this catch is why nobody
            // noticed the sweep had stopped for 21 hours.
            LOGGER.warn("retention sweep for {} failed after removing {} rows", table, removed, exception);
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
