package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Two replicas of one service relay the same outbox table.
 *
 * <p>The relay publishes before it marks rows published, so both of them used to
 * publish every row they had both selected: measured on the resident cluster, a
 * second copy of 40 percent of `events:journey-order`, 43 percent of
 * `events:post-sales` and 74 percent of `events:capacity-availability`. These
 * tests pin the election that leaves exactly one publisher.
 */
class OutboxRelayLeadershipTest {
    private static Connection connectionAnswering(boolean claimed) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(claimed);
        return connection;
    }

    @Test
    void onlyTheReplicaThatHoldsTheLockPublishes() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        // The first replica to ask takes the lock; the second is told it is held.
        Connection claiming = connectionAnswering(true);
        Connection denied = connectionAnswering(false);
        when(dataSource.getConnection()).thenReturn(claiming, denied);
        LazyRedisOutboxRelayLifecycle first = new LazyRedisOutboxRelayLifecycle(dataSource, "redis://127.0.0.1:1");
        LazyRedisOutboxRelayLifecycle second = new LazyRedisOutboxRelayLifecycle(dataSource, "redis://127.0.0.1:1");

        first.pollOnce();
        second.pollOnce();

        // The publisher could not reach Redis on port 1, so it is not ready; that
        // is what a broken relay must report. The follower publishes nothing, so
        // its readiness is the peer's, not its own, and it must not be pulled out
        // of rotation while the peer is publishing.
        assertFalse(first.isReady(), "the publisher must not claim readiness when its Redis is unreachable");
        assertTrue(second.isReady(), "a replica that does not publish must stay ready while its peer does");
    }

    @Test
    void aFollowerDoesNotOpenRedis() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection denied = connectionAnswering(false);
        when(dataSource.getConnection()).thenReturn(denied);
        LazyRedisOutboxRelayLifecycle follower = new LazyRedisOutboxRelayLifecycle(dataSource, "redis://127.0.0.1:1");

        follower.pollOnce();

        // No Redis exception is reported, and the connection it used to ask is
        // returned rather than held: a follower holds no resources at all.
        assertTrue(follower.isReady());
        verify(denied).close();
    }
}
