package com.trainticket.platformkit.persistence;

import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Starts the outbox relay without opening Redis during Spring bean construction.
 * The first scheduled poll creates the Redis connection; failures are retried
 * on later polls and exposed through {@link #isReady()} instead of aborting
 * service startup.
 */
public final class LazyRedisOutboxRelayLifecycle implements AutoCloseable {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(
        Long.parseLong(System.getenv().getOrDefault("OUTBOX_POLL_INTERVAL_MS", "50")));
    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";

    private final DataSource dataSource;
    private final String redisUrl;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile OutboxRelay relay;
    private volatile boolean redisReady;
    private volatile long nextConnectionAttemptNanos;
    private volatile Duration reconnectBackoff = Duration.ofSeconds(1);

    public LazyRedisOutboxRelayLifecycle(DataSource dataSource, String redisUrl) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.redisUrl = redisUrl == null || redisUrl.isBlank() ? DEFAULT_REDIS_URL : redisUrl;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::pollOrRetry, 0, DEFAULT_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public boolean isReady() {
        return redisReady;
    }

    private void pollOrRetry() {
        if (relay == null && System.nanoTime() < nextConnectionAttemptNanos) {
            return;
        }
        try {
            ensureRelay().pollOnce();
            redisReady = true;
            reconnectBackoff = Duration.ofSeconds(1);
        } catch (RuntimeException exception) {
            redisReady = false;
            closeRedisResources();
            nextConnectionAttemptNanos = System.nanoTime() + reconnectBackoff.toNanos();
            reconnectBackoff = reconnectBackoff.multipliedBy(2).compareTo(Duration.ofSeconds(5)) > 0
                ? Duration.ofSeconds(5)
                : reconnectBackoff.multipliedBy(2);
        }
    }

    private OutboxRelay ensureRelay() {
        OutboxRelay current = relay;
        if (current != null) {
            return current;
        }
        RedisClient newClient = RedisClient.create(redisUrl);
        StatefulRedisConnection<String, String> newConnection = newClient.connect();
        OutboxRelay newRelay = new OutboxRelay(dataSource, new LettuceRedisStreamOperations(newConnection));
        client = newClient;
        connection = newConnection;
        relay = newRelay;
        return newRelay;
    }

    private void closeRedisResources() {
        OutboxRelay currentRelay = relay;
        StatefulRedisConnection<String, String> currentConnection = connection;
        RedisClient currentClient = client;
        relay = null;
        connection = null;
        client = null;
        if (currentRelay != null) {
            currentRelay.close();
        }
        if (currentConnection != null) {
            currentConnection.close();
        }
        if (currentClient != null) {
            currentClient.shutdown();
        }
    }

    @Override
    public void close() {
        started.set(false);
        executor.shutdownNow();
        closeRedisResources();
    }
}
