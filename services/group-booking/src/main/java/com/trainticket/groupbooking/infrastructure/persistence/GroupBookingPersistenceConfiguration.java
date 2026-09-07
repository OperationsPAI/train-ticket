package com.trainticket.groupbooking.infrastructure.persistence;

import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.MigrationRunner;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Postgres wiring for group-booking, following the sibling Java services
 * (payment, journey-order, marketing-campaign, wallet-promotion, ...).
 *
 * <p>Until this class existed group-booking was the only one of the ten Java
 * services that never referenced {@link MigrationRunner}, so
 * {@code migrations/001_group_booking.sql} was shipped into the image by
 * {@code deploy/docker/group-booking/Dockerfile} and then never applied: the
 * {@code group_booking} database had zero tables.
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class GroupBookingPersistenceConfiguration {
    /**
     * Relative default used by every Java sibling. It resolves against the
     * process working directory, which is {@code /app} in the container (the
     * Dockerfile copies the migrations to {@code /app/migrations}) and the
     * module directory under Maven (so {@code services/group-booking/migrations}
     * in tests). {@code MIGRATIONS_DIR} is the repo-wide override convention and
     * is set to {@code /app/migrations} for the non-Java services.
     */
    static final String DEFAULT_MIGRATIONS_DIRECTORY = "migrations";

    @Bean(destroyMethod = "close")
    @Primary
    DataSource groupBookingDataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        return DataSources.fromDatabaseUrl(databaseUrl);
    }

    @Bean
    @Primary
    PlatformTransactionManager groupBookingTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    MigrationRunner groupBookingMigrationRunner(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        @Value("${MIGRATIONS_DIR:}") String migrationsDirectory
    ) {
        MigrationRunner runner = new MigrationRunner(dataSource, transactionManager);
        runner.run(migrationsDirectory(migrationsDirectory));
        return runner;
    }

    static Path migrationsDirectory(String configuredDirectory) {
        return Path.of(configuredDirectory == null || configuredDirectory.isBlank()
            ? DEFAULT_MIGRATIONS_DIRECTORY
            : configuredDirectory.trim());
    }
}
