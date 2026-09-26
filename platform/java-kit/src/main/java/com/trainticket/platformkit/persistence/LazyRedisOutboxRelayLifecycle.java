package com.trainticket.platformkit.persistence;

import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 *
 * <p>Only one replica publishes. Every replica used to run its own relay over
 * the same outbox table, and the relay publishes before it marks rows published,
 * so two replicas that selected the same unpublished rows both published them.
 * Measured on the resident cluster, that put a second copy of 40 percent of
 * `events:journey-order` and `events:booking-orchestration`, 43 percent of
 * `events:post-sales` and 74 percent of `events:capacity-availability` into the
 * streams, where every consumer group had to read, parse, deduplicate and
 * acknowledge it. A session advisory lock elects one publisher; the others stay
 * idle and report ready, because the relay is running -- on another instance.
 */
public final class LazyRedisOutboxRelayLifecycle implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LazyRedisOutboxRelayLifecycle.class);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(
        Long.parseLong(System.getenv().getOrDefault("OUTBOX_POLL_INTERVAL_MS", "50")));
    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";
    // Keyed on the database, not on the service: every service relays its own
    // outbox table, and two services that happen to share a database must not
    // elect the same publisher.
    private static final String CLAIM_LEADERSHIP_SQL =
        "SELECT pg_try_advisory_lock(hashtextextended('platformkit-outbox-relay:' || current_database(), 0))";
    private static final String RELEASE_LEADERSHIP_SQL =
        "SELECT pg_advisory_unlock(hashtextextended('platformkit-outbox-relay:' || current_database(), 0))";

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
    /** True once the database answered that another instance holds the publish lock. */
    private volatile boolean leadershipDenied;
    private Connection leaderConnection;

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
            executor.scheduleWithFixedDelay(this::pollOnce, 0, DEFAULT_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public boolean isReady() {
        return redisReady;
    }

    /** One poll. Package private so a test can drive it without the scheduler. */
    void pollOnce() {
        if (!holdsLeadership()) {
            // Another instance publishes this outbox. This one is healthy as long
            // as it could ask the database; a follower that could not even reach
            // the database stays unready, which is what the previous behaviour
            // reported for a relay that could not poll.
            redisReady = leadershipDenied;
            return;
        }
        if (relay == null && System.nanoTime() < nextConnectionAttemptNanos) {
            return;
        }
        try {
            ensureRelay().pollOnceAndSweep();
            redisReady = true;
            reconnectBackoff = Duration.ofSeconds(1);
        } catch (RuntimeException exception) {
            redisReady = false;
            LOGGER.warn("outbox poll failed; reconnectDelayMs={}",reconnectBackoff.toMillis(),exception);
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
        releaseLeadership();
    }

    /**
     * True while this instance is the replica that publishes the outbox.
     *
     * <p>The lock is session scoped and held on a connection this instance keeps
     * open, so losing the process -- or the connection -- releases it and another
     * replica takes over on its next poll.
     */
    private synchronized boolean holdsLeadership() {
        Connection current = leaderConnection;
        if (current != null) {
            try {
                if (current.isValid(2)) {
                    leadershipDenied = false;
                    return true;
                }
            } catch (SQLException failure) {
                LOGGER.warn("outbox relay leadership check failed: {}", failure.getMessage());
            }
            releaseLeadership();
        }
        try {
            Connection candidate = dataSource.getConnection();
            try (PreparedStatement statement = candidate.prepareStatement(CLAIM_LEADERSHIP_SQL);
                 ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    leaderConnection = candidate;
                    leadershipDenied = false;
                    return true;
                }
            }
            candidate.close();
            leadershipDenied = true;
            return false;
        } catch (SQLException failure) {
            LOGGER.warn("outbox relay leadership acquisition failed: {}", failure.getMessage());
            leadershipDenied = false;
            return false;
        }
    }

    private synchronized void releaseLeadership() {
        Connection current = leaderConnection;
        leaderConnection = null;
        if (current == null) {
            return;
        }
        try (PreparedStatement statement = current.prepareStatement(RELEASE_LEADERSHIP_SQL)) {
            statement.execute();
        } catch (SQLException failure) {
            LOGGER.warn("outbox relay leadership release failed: {}", failure.getMessage());
        } finally {
            try {
                current.close();
            } catch (SQLException failure) {
                LOGGER.warn("outbox relay leadership connection close failed: {}", failure.getMessage());
            }
        }
    }
}
