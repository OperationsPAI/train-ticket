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
        config.setMaximumPoolSize(Integer.parseInt(
            System.getenv().getOrDefault("HIKARI_MAX_POOL_SIZE", "10")));
        // Keep warm spares so a single server-closed connection does not leave the
        // pool empty and force an on-demand connect while a request is already in
        // flight. A minimumIdle of 1 produced "Connection is not available, request
        // timed out ... (total=1, active=1)" whenever a closed connection coincided
        // with a burst.
        config.setMinimumIdle(Integer.parseInt(
            System.getenv().getOrDefault("HIKARI_MIN_IDLE", "3")));
        config.setPoolName("platform-java-kit-postgres");
        config.setKeepaliveTime(30_000);
        // Recycle well before any server-side or proxy idle cutoff so a stale
        // connection is never handed to a request.
        config.setMaxLifetime(300_000);
        // Headroom to acquire or replace a connection while Postgres is briefly slow
        // (reconnect storms) instead of surfacing a 500 to the caller.
        config.setConnectionTimeout(15_000);
        config.setValidationTimeout(3_000);
        // connectionTestQuery stays unset: the pgjdbc JDBC4 isValid() aliveness check
        // on checkout already evicts a closed connection and hands out a fresh one,
        // which is cheaper and more reliable than a test query.
        return new HikariDataSource(config);
    }
}
