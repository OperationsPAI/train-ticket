package com.trainticket.groupbooking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.messaging.EventPublisher;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
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

    @Bean
    @Primary
    IdempotencyStore groupBookingIdempotencyStore(DataSource dataSource, ObjectMapper mapper) {
        return new DbIdempotencyStore(dataSource, mapper);
    }

    @Bean
    @Primary
    EventPublisher groupBookingOutboxPublisher(DataSource dataSource, ObjectMapper mapper) {
        OutboxAppender outbox = new OutboxAppender(dataSource, mapper);
        return outbox::append;
    }

    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "group-booking.outbox-relay.enabled", havingValue = "true", matchIfMissing = true)
    LazyRedisOutboxRelayLifecycle groupBookingOutboxRelay(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        LazyRedisOutboxRelayLifecycle relay = new LazyRedisOutboxRelayLifecycle(dataSource, redisUrl);
        relay.start();
        return relay;
    }

    static Path migrationsDirectory(String configuredDirectory) {
        return Path.of(configuredDirectory == null || configuredDirectory.isBlank()
            ? DEFAULT_MIGRATIONS_DIRECTORY
            : configuredDirectory.trim());
    }
}
