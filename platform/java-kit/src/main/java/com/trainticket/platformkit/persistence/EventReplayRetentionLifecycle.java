package com.trainticket.platformkit.persistence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Removes expired replay records in bounded, non-overlapping batches. */
public final class EventReplayRetentionLifecycle implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventReplayRetentionLifecycle.class);
    private static final int DELETE_BATCH_SIZE = 1_000;
    private final EventReplayArchive archive;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean sweeping = new AtomicBoolean();

    public EventReplayRetentionLifecycle(EventReplayArchive archive) {
        this(archive, Duration.ofMinutes(1));
    }

    EventReplayRetentionLifecycle(EventReplayArchive archive, Duration interval) {
        this.archive = Objects.requireNonNull(archive, "archive is required");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-replay-retention");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void sweepSafely() {
        if (!sweeping.compareAndSet(false, true)) return;
        try {
            int removed;
            do {
                removed = archive.deleteExpired(DELETE_BATCH_SIZE);
            } while (removed == DELETE_BATCH_SIZE);
        } catch (RuntimeException error) {
            LOGGER.warn("event replay retention sweep failed; retained events remain available", error);
        } finally {
            sweeping.set(false);
        }
    }

    @Override public void close() {
        executor.shutdownNow();
    }
}
