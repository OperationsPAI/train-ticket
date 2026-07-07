package com.trainticket.platformkit.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public final class DataSources {
    private DataSources() {
    }

    public static DataSource fromDatabaseUrl(String databaseUrl) {
        DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(parsed.jdbcUrl());
        config.setUsername(parsed.username());
        config.setPassword(parsed.password());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setPoolName("platform-java-kit-postgres");
        return new HikariDataSource(config);
    }
}
