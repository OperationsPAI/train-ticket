package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.application.EventPublisher;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.EventReplayArchive;
import com.trainticket.platformkit.persistence.EventReplayArchiveController;
import com.trainticket.platformkit.persistence.EventReplayRetentionLifecycle;
import com.trainticket.platformkit.persistence.EventReplayRetentionPolicy;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
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

@Configuration
@EnableTransactionManagement
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class PaymentPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    DataSource paymentDataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        return DataSources.fromDatabaseUrl(databaseUrl);
    }

    @Bean
    @Primary
    PlatformTransactionManager paymentTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    MigrationRunner paymentMigrationRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
        MigrationRunner runner = new MigrationRunner(dataSource, transactionManager);
        runner.run(Path.of("migrations"));
        return runner;
    }

    @Bean
    OutboxAppender paymentOutboxAppender(DataSource dataSource, ObjectMapper objectMapper) {
        return new OutboxAppender(dataSource, objectMapper, EventReplayRetentionPolicy.fromEnvironment(),
            java.util.Set.of("PaymentCaptured", "RefundRequested", "RefundSettled", "RefundFailed", "PaymentRefunded"));
    }

    @Bean
    EventReplayArchive paymentEventReplayArchive(DataSource dataSource) {
        return new EventReplayArchive(dataSource);
    }

    @Bean
    EventReplayArchiveController paymentEventReplayArchiveController(EventReplayArchive archive) {
        return new EventReplayArchiveController(archive, "payment");
    }

    @Bean(destroyMethod = "close")
    EventReplayRetentionLifecycle paymentEventReplayRetentionLifecycle(EventReplayArchive archive) {
        return new EventReplayRetentionLifecycle(archive);
    }

    @Bean
    @Primary
    EventPublisher paymentOutboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) {
        return new TransactionalOutboxEventPublisher(outboxAppender, objectMapper);
    }

    @Bean
    @Primary
    IdempotencyStore paymentDbIdempotencyStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new DbIdempotencyStore(dataSource, objectMapper);
    }

    /**
     * The kit's relay, which elects one publisher per database with a session
     * advisory lock.
     *
     * This service used to build its own relay instead, and every replica ran it:
     * the relay publishes before it marks rows published, so two replicas that
     * selected the same unpublished rows published both. Measured on 2026-09-27,
     * 885 of the last 2000 entries on `events:payment` carried an event id that
     * was already in the stream -- the same defect the session lock was added to
     * remove for the other services, left behind in this one. Every group that
     * reads the payment stream parsed, deduplicated and acknowledged each of
     * those twice.
     */
    @Bean(destroyMethod = "close")
    LazyRedisOutboxRelayLifecycle outboxRelayLifecycle(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        LazyRedisOutboxRelayLifecycle lifecycle = new LazyRedisOutboxRelayLifecycle(dataSource, redisUrl);
        lifecycle.start();
        return lifecycle;
    }
}
