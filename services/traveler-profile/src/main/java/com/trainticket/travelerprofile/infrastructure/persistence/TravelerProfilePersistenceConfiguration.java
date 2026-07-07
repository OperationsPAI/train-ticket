package com.trainticket.travelerprofile.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.PublishFailedException;
import java.nio.file.Path;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class TravelerProfilePersistenceConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    DataSource dataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        return DataSources.fromDatabaseUrl(databaseUrl);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    MigrationRunner migrationRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
        MigrationRunner runner = new MigrationRunner(dataSource, transactionManager);
        runner.run(Path.of("migrations"));
        return runner;
    }

    @Bean
    OutboxAppender outboxAppender(DataSource dataSource, ObjectMapper objectMapper) {
        return new OutboxAppender(dataSource, objectMapper);
    }

    @Bean
    @Primary
    EventPublisher outboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) {
        return new TransactionalOutboxEventPublisher(outboxAppender, objectMapper);
    }

    @Bean
    @Primary
    IdempotencyStore dbIdempotencyStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new DbIdempotencyStore(dataSource, objectMapper);
    }

    @Bean
    @Primary
    PostgresTravelerProfileStore postgresTravelerProfileStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new PostgresTravelerProfileStore(dataSource, objectMapper);
    }

    @Bean
    @Primary
    PostgresConsumedEventLog postgresConsumedEventLog(DataSource dataSource) {
        return new PostgresConsumedEventLog(dataSource);
    }

    @Bean(destroyMethod = "close")
    LazyRedisOutboxRelayLifecycle outboxRelayLifecycle(
        DataSource dataSource,
        @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl
    ) {
        LazyRedisOutboxRelayLifecycle lifecycle = new LazyRedisOutboxRelayLifecycle(dataSource, redisUrl);
        lifecycle.start();
        return lifecycle;
    }

    static final class TransactionalOutboxEventPublisher implements EventPublisher {
        private final OutboxAppender outboxAppender;
        private final ObjectMapper objectMapper;

        TransactionalOutboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) {
            this.outboxAppender = Objects.requireNonNull(outboxAppender, "outboxAppender is required");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        }

        @Override
        public void publish(com.trainticket.platformkit.messaging.EventEnvelope envelope) throws PublishFailedException {
            try {
                outboxAppender.append(envelope, objectMapper.writeValueAsString(envelope));
            } catch (JsonProcessingException exception) {
                throw new PublishFailedException("event envelope could not be serialized", exception);
            }
        }
    }
}
