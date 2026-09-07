package com.trainticket.groupbooking.infrastructure.persistence;

import com.trainticket.platformkit.persistence.MigrationRunner;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Readiness for group-booking, combining the two probes already used across the
 * fleet: the {@code SELECT 1} liveness check on the pool (journey-order,
 * finance-settlement, booking-orchestration, admin-audit, post-sales,
 * traveler-profile) and the migration check (payment).
 *
 * <p>group-booking previously answered {@code /readyz} with a hard-coded
 * {@code ready}, so the pod reported healthy for over a day while its database
 * had no schema at all. The migration check is the part that can detect that.
 */
@Component
public class GroupBookingReadiness {
    private final Optional<DataSource> dataSource;
    private final Optional<MigrationRunner> migrationRunner;

    @Autowired
    public GroupBookingReadiness(Optional<DataSource> dataSource, Optional<MigrationRunner> migrationRunner) {
        this.dataSource = dataSource;
        this.migrationRunner = migrationRunner;
    }

    public GroupBookingReadiness(Optional<DataSource> dataSource) {
        this(dataSource, Optional.empty());
    }

    public boolean isReady() {
        return migrationsApplied() && databaseReady();
    }

    private boolean migrationsApplied() {
        return migrationRunner.map(MigrationRunner::isReady).orElse(true);
    }

    private boolean databaseReady() {
        if (dataSource.isEmpty()) {
            return true;
        }
        try (Connection connection = dataSource.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
