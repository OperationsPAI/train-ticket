package com.trainticket.groupbooking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trainticket.groupbooking.HealthController;
import com.trainticket.platformkit.persistence.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * group-booking's {@code /readyz} used to return a hard-coded {@code ready}, so
 * the pod reported healthy for over a day with an empty database. These tests
 * pin the fleet behaviour: a failed migration must surface as 503.
 *
 * <p>No Postgres is involved -- the DataSource and the migration bookkeeping are
 * doubles. These prove the readiness wiring reacts to migration failure and to
 * an unusable connection; they do not prove anything about a real database.
 */
class GroupBookingReadinessTest {
    @Test
    void readinessIsFalseWhenMigrationsFailed() {
        MigrationRunner runner = failedRunner();

        GroupBookingReadiness readiness =
            new GroupBookingReadiness(Optional.of(healthyDataSource()), Optional.of(runner));

        assertThat(runner.isReady()).isFalse();
        assertThat(readiness.isReady()).isFalse();
    }

    /**
     * The regression in one assertion: a service whose migrations never ran must
     * not answer 200 on /readyz.
     */
    @Test
    void readyzReports503WhenMigrationsFailed() {
        HealthController controller = new HealthController(
            new GroupBookingReadiness(Optional.of(healthyDataSource()), Optional.of(failedRunner())));

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "not_ready");
    }

    @Test
    void readyzReports200WhenMigrationsApplied() {
        HealthController controller = new HealthController(
            new GroupBookingReadiness(Optional.of(healthyDataSource()), Optional.of(appliedRunner())));

        ResponseEntity<Map<String, Object>> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ready");
    }

    @Test
    void readinessIsFalseWhenTheConnectionCannotBeUsed() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        assertThat(new GroupBookingReadiness(Optional.of(dataSource), Optional.of(appliedRunner())).isReady())
            .isFalse();
    }

    /**
     * With no DATABASE_URL the persistence configuration does not activate, so
     * neither bean exists. That must stay ready, matching every sibling and
     * keeping the in-memory test/local profile working.
     */
    @Test
    void readinessIsTrueWhenPersistenceIsNotConfigured() {
        assertThat(new GroupBookingReadiness(Optional.empty()).isReady()).isTrue();
        assertThat(new GroupBookingReadiness(Optional.empty(), Optional.empty()).isReady()).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    /** A runner whose run() threw, exactly as a broken migration leaves it. */
    private static MigrationRunner failedRunner() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("no schema"))
            .when(jdbc).execute(anyString());
        MigrationRunner runner = new MigrationRunner(jdbc, inlineTransactionTemplate());
        try {
            runner.run(Path.of("migrations"));
        } catch (RuntimeException expected) {
            // run() rethrows after recording the failure; that is the state under test.
        }
        return runner;
    }

    private static MigrationRunner appliedRunner() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        MigrationRunner runner = new MigrationRunner(jdbc, inlineTransactionTemplate());
        runner.run(Path.of("migrations"));
        return runner;
    }

    private static TransactionTemplate inlineTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }

    private static DataSource healthyDataSource() {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute("SELECT 1")).thenReturn(true);
            return dataSource;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
