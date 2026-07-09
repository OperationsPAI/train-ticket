package com.trainticket.walletpromotion.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.*;
import com.trainticket.walletpromotion.application.PromotionRepository;
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
public class WalletPromotionPersistenceConfiguration {
    @Bean(destroyMethod = "close") @Primary DataSource walletPromotionDataSource(@Value("${DATABASE_URL:}") String databaseUrl) { return DataSources.fromDatabaseUrl(databaseUrl); }
    @Bean @Primary PlatformTransactionManager walletPromotionTransactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean MigrationRunner walletPromotionMigrationRunner(DataSource ds, PlatformTransactionManager tx) { MigrationRunner r = new MigrationRunner(ds, tx); r.run(Path.of("migrations")); return r; }
    @Bean @Primary IdempotencyStore walletPromotionDbIdempotencyStore(DataSource ds, ObjectMapper mapper) { return new DbIdempotencyStore(ds, mapper); }
    @Bean PromotionRepository postgresPromotionRepository(DataSource ds, ObjectMapper mapper) { return new PostgresPromotionRepository(ds, mapper, new OutboxAppender(ds, mapper)); }
    @Bean(destroyMethod = "close") RelayLifecycle walletPromotionOutboxRelayLifecycle(DataSource ds, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) { return new RelayLifecycle(ds, redisUrl); }
    static final class RelayLifecycle implements AutoCloseable { private final LazyRedisOutboxRelayLifecycle relay; RelayLifecycle(DataSource ds, String redisUrl) { relay = new LazyRedisOutboxRelayLifecycle(ds, redisUrl); } @PostConstruct void start(){ relay.start(); } public void close(){ relay.close(); } }
}
