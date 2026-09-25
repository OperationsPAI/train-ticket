package com.trainticket.bookingorchestration.observability;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Elects a single saga stage reporter with a PostgreSQL session advisory lock.
 *
 * <p>The lock is held on one connection for as long as this instance is the leader. Closing that
 * connection, on shutdown or on failure, releases the lock and lets another instance take over,
 * where it rebuilds the levels from the same durable rows.
 */
@Component
@ConditionalOnBean(DataSource.class)
public final class SagaStageReporterLeadership {
    private static final Logger LOGGER = LoggerFactory.getLogger(SagaStageReporterLeadership.class);
    private static final long LOCK_KEY = 0x54_52_41_49_4E_54_49_43L;

    private final DataSource dataSource;
    private Connection leaderConnection;

    @Autowired
    public SagaStageReporterLeadership(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public synchronized boolean isLeader() {
        if (leaderConnection != null) {
            if (isAlive(leaderConnection)) {
                return true;
            }
            releaseLeaderConnection();
        }
        try {
            Connection candidate = dataSource.getConnection();
            if (tryAcquire(candidate)) {
                leaderConnection = candidate;
                LOGGER.info("This instance reports booking saga stage levels");
                return true;
            }
            candidate.close();
        } catch (SQLException failure) {
            LOGGER.warn("Booking saga stage leadership check failed: {}", failure.getMessage());
        }
        return false;
    }

    @PreDestroy
    public synchronized void release() {
        releaseLeaderConnection();
    }

    private static boolean tryAcquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static boolean isAlive(Connection connection) {
        try {
            return connection.isValid(2);
        } catch (SQLException failure) {
            return false;
        }
    }

    private void releaseLeaderConnection() {
        Connection connection = leaderConnection;
        leaderConnection = null;
        if (connection == null) {
            return;
        }
        try {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
        } catch (SQLException failure) {
            LOGGER.warn("Booking saga stage leadership release failed: {}", failure.getMessage());
        } finally {
            try {
                connection.close();
            } catch (SQLException failure) {
                LOGGER.warn("Booking saga stage leadership connection close failed: {}", failure.getMessage());
            }
        }
    }
}
