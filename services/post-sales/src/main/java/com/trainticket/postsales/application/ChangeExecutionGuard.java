package com.trainticket.postsales.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Coalesces concurrent execution attempts for one case inside this process.
 *
 * <p>The old guard held a PostgreSQL session advisory lock across every downstream HTTP call and
 * put a process-wide semaphore in front of it. Once sixteen unrelated cases were waiting on
 * Booking or Capacity, every later case was returned unchanged. The load generator then spent a
 * complete polling interval retrying an operation that had never been attempted.</p>
 *
 * <p>A short database lease keeps the same case single-flight across replicas. The connection is
 * returned immediately after claim/renew/release; no database session is held while another
 * service is called. Independent cases have independent rows and do not share a process-wide
 * execution budget.</p>
 */
@Component
public final class ChangeExecutionGuard implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeExecutionGuard.class);
    static final java.time.Duration DEFAULT_LEASE_DURATION = java.time.Duration.ofSeconds(15);
    static final java.time.Duration DEFAULT_HEARTBEAT_INTERVAL = java.time.Duration.ofSeconds(3);

    private final ConcurrentHashMap<String, LockEntry> caseLocks = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final long leaseMillis;
    private final long heartbeatMillis;
    private final ScheduledExecutorService heartbeats;

    public ChangeExecutionGuard() {
        this((DataSource) null, DEFAULT_LEASE_DURATION, DEFAULT_HEARTBEAT_INTERVAL);
    }

    @Autowired
    public ChangeExecutionGuard(@Qualifier("changeExecutionLockDataSource") ObjectProvider<DataSource> dataSource) {
        this(dataSource.getIfAvailable(), DEFAULT_LEASE_DURATION, DEFAULT_HEARTBEAT_INTERVAL);
    }

    ChangeExecutionGuard(DataSource dataSource, java.time.Duration leaseDuration, java.time.Duration heartbeatInterval) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("leaseDuration must be positive");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
            || heartbeatInterval.compareTo(leaseDuration) >= 0) throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.leaseMillis = leaseDuration.toMillis();
        this.heartbeatMillis = heartbeatInterval.toMillis();
        this.heartbeats = dataSource == null ? null : Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "post-sales-execution-lease");
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> T execute(String caseId, Supplier<T> action, Supplier<T> busy) {
        String key = PostSalesMapper.stripCasePrefix(caseId);
        LockEntry entry = caseLocks.compute(key, (ignored, current) -> {
            LockEntry selected = current == null ? new LockEntry() : current;
            selected.references++;
            return selected;
        });
        if (!entry.lock.tryLock()) {
            releaseReference(key, entry);
            return busy.get();
        }
        Lease lease = null;
        try {
            if (jdbc != null) {
                lease = claim(key);
                if (lease == null) return busy.get();
            }
            return action.get();
        } finally {
            if (lease != null) lease.close();
            entry.lock.unlock();
            releaseReference(key, entry);
        }
    }

    private Lease claim(String caseId) {
        String owner = java.util.UUID.randomUUID().toString();
        var owners = jdbc.query("""
            INSERT INTO post_sales_execution_leases(case_id,owner_id,lease_until,updated_at)
            VALUES (?, ?, clock_timestamp() + (? * interval '1 millisecond'), clock_timestamp())
            ON CONFLICT(case_id) DO UPDATE
                SET owner_id=EXCLUDED.owner_id,
                    lease_until=EXCLUDED.lease_until,
                    updated_at=EXCLUDED.updated_at
              WHERE post_sales_execution_leases.lease_until <= clock_timestamp()
            RETURNING owner_id
            """, (row, index) -> row.getString(1), caseId, owner, leaseMillis);
        if (owners.isEmpty() || !owner.equals(owners.getFirst())) return null;
        AtomicBoolean lost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
            () -> renew(caseId, owner, lost), heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        return new Lease(caseId, owner, heartbeat, lost);
    }

    private void renew(String caseId, String owner, AtomicBoolean lost) {
        try {
            int updated = jdbc.update("""
                UPDATE post_sales_execution_leases
                   SET lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                       updated_at=clock_timestamp()
                 WHERE case_id=? AND owner_id=? AND lease_until > clock_timestamp()
                """, leaseMillis, caseId, owner);
            if (updated != 1) lost.set(true);
        } catch (RuntimeException error) {
            lost.set(true);
            LOGGER.error("post-sales execution lease heartbeat failed case={} owner={}", caseId, owner, error);
        }
    }

    private void releaseReference(String key, LockEntry expected) {
        caseLocks.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) return current;
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    int trackedCases() {
        return caseLocks.size();
    }

    @Override
    public void close() {
        if (heartbeats != null) heartbeats.shutdownNow();
    }

    private final class Lease implements AutoCloseable {
        private final String caseId;
        private final String owner;
        private final ScheduledFuture<?> heartbeat;
        private final AtomicBoolean lost;

        private Lease(String caseId, String owner, ScheduledFuture<?> heartbeat, AtomicBoolean lost) {
            this.caseId = caseId;
            this.owner = owner;
            this.heartbeat = heartbeat;
            this.lost = lost;
        }

        @Override
        public void close() {
            heartbeat.cancel(false);
            try {
                jdbc.update("DELETE FROM post_sales_execution_leases WHERE case_id=? AND owner_id=?", caseId, owner);
            } catch (RuntimeException error) {
                LOGGER.warn("post-sales execution lease release failed case={} owner={}; it will expire", caseId, owner, error);
            }
            if (lost.get()) throw new IllegalStateException("post-sales execution lease was lost for " + caseId);
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
