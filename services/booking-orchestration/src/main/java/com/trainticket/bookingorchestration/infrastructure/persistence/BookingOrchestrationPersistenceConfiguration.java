package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.application.EventPublisher;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
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

@Configuration
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class BookingOrchestrationPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    DataSource bookingorchestrationDataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        return DataSources.fromDatabaseUrl(databaseUrl);
    }

    @Bean
    @Primary
    PlatformTransactionManager bookingorchestrationTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    MigrationRunner bookingorchestrationMigrationRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
        MigrationRunner runner = new MigrationRunner(dataSource, transactionManager);
        runner.run(Path.of("migrations"));
        return runner;
    }

    @Bean
    OutboxAppender bookingorchestrationOutboxAppender(DataSource dataSource, ObjectMapper objectMapper) {
        return new OutboxAppender(dataSource, objectMapper);
    }

    @Bean
    @Primary
    EventPublisher bookingorchestrationOutboxEventPublisher(OutboxAppender outboxAppender, ObjectMapper objectMapper) {
        return new TransactionalOutboxEventPublisher(outboxAppender, objectMapper);
    }

    @Bean
    @Primary
    IdempotencyStore bookingorchestrationDbIdempotencyStore(DataSource dataSource, ObjectMapper objectMapper) {
        return new DbIdempotencyStore(dataSource, objectMapper);
    }

    @Bean(destroyMethod = "close")
    LazyRedisOutboxRelayLifecycle outboxRelayLifecycle(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        return new LazyRedisOutboxRelayLifecycle(dataSource, redisUrl);
    }
}
