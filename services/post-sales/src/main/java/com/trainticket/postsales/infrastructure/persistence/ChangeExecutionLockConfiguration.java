package com.trainticket.postsales.infrastructure.persistence;

import com.trainticket.platformkit.persistence.DatabaseUrl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class ChangeExecutionLockConfiguration {
    @Bean(destroyMethod = "close")
    DataSource changeExecutionLockDataSource(@Value("${DATABASE_URL}") String databaseUrl) {
        DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(parsed.jdbcUrl());
        config.setUsername(parsed.username());
        config.setPassword(parsed.password());
        config.setPoolName("post-sales-change-leases");
        // Lease operations are one short statement. A small dedicated pool keeps them independent
        // from business transactions without reserving one PostgreSQL connection per active case.
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        config.setKeepaliveTime(30000);
        config.setMaxLifetime(300000);
        return new HikariDataSource(config);
    }
}
