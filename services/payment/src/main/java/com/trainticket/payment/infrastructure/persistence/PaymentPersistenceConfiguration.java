package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.application.EventPublisher;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.trainticket.platformkit.persistence.OutboxRelay;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PostConstruct;
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
        return new OutboxAppender(dataSource, objectMapper);
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

    @Bean(destroyMethod = "close")
    PaymentOutboxRelayLifecycle paymentOutboxRelayLifecycle(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        return new PaymentOutboxRelayLifecycle(dataSource, redisUrl);
    }

    static final class PaymentOutboxRelayLifecycle implements AutoCloseable {
        private final RedisClient client;
        private final StatefulRedisConnection<String, String> connection;
        private final OutboxRelay relay;

        PaymentOutboxRelayLifecycle(DataSource dataSource, String redisUrl) {
            this.client = RedisClient.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl);
            this.connection = client.connect();
            this.relay = new OutboxRelay(dataSource, new LettuceRedisStreamOperations(connection));
        }

        @PostConstruct
        void start() {
            relay.start();
        }

        @Override
        public void close() {
            relay.close();
            connection.close();
            client.shutdown();
        }
    }
}
