package com.trainticket.financesettlement.infrastructure.persistence;

import com.trainticket.platformkit.persistence.LazyRedisOutboxRelayLifecycle;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class FinanceSettlementReadiness {
    private final Optional<DataSource> dataSource;
    private final Optional<LazyRedisOutboxRelayLifecycle> outboxRelay;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceSettlementReadiness(Optional<DataSource> dataSource, Optional<LazyRedisOutboxRelayLifecycle> outboxRelay) {
        this.dataSource = dataSource;
        this.outboxRelay = outboxRelay;
    }

    public FinanceSettlementReadiness(Optional<DataSource> dataSource) {
        this(dataSource, Optional.empty());
    }

    public boolean isReady() {
        return databaseReady() && outboxRelay.map(LazyRedisOutboxRelayLifecycle::isReady).orElse(true);
    }

    private boolean databaseReady() {
        if (dataSource.isEmpty()) {
            return true;
        }
        try (Connection connection = dataSource.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
