package com.trainticket.travelerprofile.infrastructure.persistence;

import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TravelerReadiness {
    private final Optional<DataSource> dataSource;
    private final Optional<LazyRedisOutboxRelayLifecycle> outboxRelay;

    public TravelerReadiness(Optional<DataSource> dataSource) {
        this(dataSource, Optional.empty());
    }

    @Autowired
    public TravelerReadiness(Optional<DataSource> dataSource, Optional<LazyRedisOutboxRelayLifecycle> outboxRelay) {
        this.dataSource = dataSource;
        this.outboxRelay = outboxRelay;
    }

    public boolean isReady() {
        return isDatabaseReady() && outboxRelay.map(LazyRedisOutboxRelayLifecycle::isReady).orElse(true);
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
