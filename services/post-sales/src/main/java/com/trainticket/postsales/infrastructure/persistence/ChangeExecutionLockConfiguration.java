package com.trainticket.postsales.infrastructure.persistence;

import com.trainticket.platformkit.persistence.DatabaseUrl;
import com.trainticket.postsales.application.ChangeExecutionGuard;
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
        config.setPoolName("post-sales-change-locks");
        config.setMaximumPoolSize(ChangeExecutionGuard.MAX_CONCURRENT_EXECUTIONS);
        config.setMinimumIdle(ChangeExecutionGuard.MAX_CONCURRENT_EXECUTIONS);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        config.setKeepaliveTime(30000);
        config.setMaxLifetime(300000);
        HikariDataSource pool=new HikariDataSource(config);
        var connections=new java.util.ArrayList<java.sql.Connection>();
        try {
            for(int index=0;index<ChangeExecutionGuard.MAX_CONCURRENT_EXECUTIONS;index++)connections.add(pool.getConnection());
            for(var connection:connections)connection.close();
            return pool;
        } catch(java.sql.SQLException error) {
            pool.close();
            throw new IllegalStateException("change execution lock pool initialization failed",error);
        }
    }
}
