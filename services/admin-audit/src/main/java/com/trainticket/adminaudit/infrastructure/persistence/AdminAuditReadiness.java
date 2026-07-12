package com.trainticket.adminaudit.infrastructure.persistence;

import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import com.trainticket.adminaudit.adapters.messaging.RedisMessagingConfiguration.RedisMessagingReadiness;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditReadiness {
    private final Optional<DataSource> dataSource;
    private final Optional<LazyRedisOutboxRelayLifecycle> outboxRelay;
    private final Optional<RedisMessagingReadiness> redisMessaging;

    public AdminAuditReadiness(Optional<DataSource> dataSource) {
        this(dataSource, Optional.empty(), Optional.empty());
    }

    @Autowired
    public AdminAuditReadiness(
        Optional<DataSource> dataSource,
        Optional<LazyRedisOutboxRelayLifecycle> outboxRelay,
        Optional<RedisMessagingReadiness> redisMessaging
    ) {
        this.dataSource = dataSource;
        this.outboxRelay = outboxRelay;
        this.redisMessaging = redisMessaging;
    }

    public boolean isReady() {
        return isDatabaseReady()
            && outboxRelay.map(LazyRedisOutboxRelayLifecycle::isReady).orElse(true)
            && redisMessaging.map(RedisMessagingReadiness::isReady).orElse(true);
    }

    private boolean isDatabaseReady() {
        return dataSource.map(dataSource -> {
            try {
                new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }).orElse(true);
    }
}
