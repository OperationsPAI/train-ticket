package com.trainticket.postsales.application;

import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class ChangeExecutionGuard {
    private final ObjectProvider<DataSource> dataSource;
    public static final int MAX_CONCURRENT_EXECUTIONS = 16;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(MAX_CONCURRENT_EXECUTIONS);

    public ChangeExecutionGuard(@Qualifier("changeExecutionLockDataSource") ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T execute(String caseId, Supplier<T> action, Supplier<T> busy) {
        if (!permits.tryAcquire()) return busy.get();
        try { return executeWithPermit(caseId, action, busy); }
        finally { permits.release(); }
    }

    private <T> T executeWithPermit(String caseId, Supplier<T> action, Supplier<T> busy) {
        String key = "post-sales-change-execution:" + PostSalesMapper.stripCasePrefix(caseId);
        try (var connection = dataSource.getObject().getConnection()) {
            boolean locked;
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) { result.next(); locked = result.getBoolean(1); }
            }
            if (!locked) return busy.get();
            try {
                return action.get();
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))")) {
                    statement.setString(1, key);
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("change execution lock was lost");
                    }
                } catch (SQLException error) {
                    connection.abort(Runnable::run);
                    throw error;
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("change execution lock failed", error);
        }
    }
}
