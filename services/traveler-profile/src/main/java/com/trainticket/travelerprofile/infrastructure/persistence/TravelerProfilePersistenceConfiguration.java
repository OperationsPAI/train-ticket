package com.trainticket.travelerprofile.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.platformkit.persistence.OutboxRelay;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.PublishFailedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PostConstruct;
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
    @Bean(destroyMethod = "close") @Primary DataSource dataSource(@Value("${DATABASE_URL:}") String databaseUrl) { return DataSources.fromDatabaseUrl(databaseUrl); }
    @Bean @Primary PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
    @Bean MigrationRunner migrationRunner(DataSource dataSource, PlatformTransactionManager tx) { MigrationRunner runner = new MigrationRunner(dataSource, tx); runner.run(Path.of("migrations")); return runner; }
    @Bean OutboxAppender outboxAppender(DataSource dataSource, ObjectMapper objectMapper) { return new OutboxAppender(dataSource, objectMapper); }
    @Bean @Primary EventPublisher outboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) { return new TransactionalOutboxEventPublisher(outboxAppender, objectMapper); }
    @Bean @Primary IdempotencyStore dbIdempotencyStore(DataSource dataSource, ObjectMapper objectMapper) { return new DbIdempotencyStore(dataSource, objectMapper); }
    @Bean(destroyMethod = "close") OutboxRelayLifecycle outboxRelayLifecycle(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) { return new OutboxRelayLifecycle(dataSource, redisUrl); }

    static final class TransactionalOutboxEventPublisher implements EventPublisher {
        private final OutboxAppender outboxAppender; private final ObjectMapper objectMapper;
        TransactionalOutboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) { this.outboxAppender = Objects.requireNonNull(outboxAppender); this.objectMapper = Objects.requireNonNull(objectMapper); }
        @Override public void publish(com.trainticket.platformkit.messaging.EventEnvelope envelope) throws PublishFailedException {
            try { outboxAppender.append(envelope, objectMapper.writeValueAsString(envelope)); }
            catch (JsonProcessingException exception) { throw new PublishFailedException("event envelope could not be serialized", exception); }
        }
    }

    static final class OutboxRelayLifecycle implements AutoCloseable {
        private final DataSource dataSource; private final String redisUrl; private RedisClient client; private StatefulRedisConnection<String, String> connection; private OutboxRelay relay;
        OutboxRelayLifecycle(DataSource dataSource, String redisUrl) { this.dataSource = dataSource; this.redisUrl = redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl; }
        @PostConstruct void start() { client = RedisClient.create(redisUrl); connection = client.connect(); relay = new OutboxRelay(dataSource, new LettuceRedisStreamOperations(connection)); relay.start(); }
        @Override public void close() { if (relay != null) relay.close(); if (connection != null) connection.close(); if (client != null) client.shutdown(); }
    }
}
