package com.trainticket.marketingcampaign.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.marketingcampaign.application.CampaignRepository;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.persistence.DataSources;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
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
public class MarketingCampaignPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    @Primary
    DataSource marketingCampaignDataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        return DataSources.fromDatabaseUrl(databaseUrl);
    }

    @Bean
    @Primary
    PlatformTransactionManager marketingCampaignTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    MigrationRunner marketingCampaignMigrationRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
        MigrationRunner runner = new MigrationRunner(dataSource, transactionManager);
        runner.run(Path.of("migrations"));
        return runner;
    }

    @Bean
    @Primary
    IdempotencyStore marketingCampaignDbIdempotencyStore(DataSource dataSource, ObjectMapper mapper) {
        return new DbIdempotencyStore(dataSource, mapper);
    }

    @Bean
    CampaignRepository postgresCampaignRepository(DataSource dataSource, ObjectMapper mapper) {
        return new PostgresCampaignRepository(dataSource, mapper, new OutboxAppender(dataSource, mapper));
    }

    @Bean(destroyMethod = "close")
    RelayLifecycle marketingCampaignOutboxRelayLifecycle(DataSource dataSource, @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        return new RelayLifecycle(dataSource, redisUrl);
    }

    static final class RelayLifecycle implements AutoCloseable {
        private final LazyRedisOutboxRelayLifecycle relay;

        RelayLifecycle(DataSource dataSource, String redisUrl) {
            relay = new LazyRedisOutboxRelayLifecycle(dataSource, redisUrl);
        }

        @PostConstruct
        void start() {
            relay.start();
        }

        @Override
        public void close() {
            relay.close();
        }
    }
}
