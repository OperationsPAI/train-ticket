package com.trainticket.financesettlement.infrastructure.persistence;

import com.trainticket.financesettlement.application.ConsumedEventLogRepository;
import com.trainticket.financesettlement.domain.ConsumedEventLog;
import com.trainticket.platformkit.persistence.ProcessedEventStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresConsumedEventLogRepository implements ConsumedEventLogRepository {
    private final ProcessedEventStore store;

    public PostgresConsumedEventLogRepository(DataSource dataSource) {
        this.store = new ProcessedEventStore(dataSource);
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return store.isProcessed(eventId);
    }

    @Override
    public void save(ConsumedEventLog log) {
        recordIfNew(log);
    }

    @Override
    public boolean recordIfNew(ConsumedEventLog log) {
        return store.recordIfNew(log.eventId(), log.source());
    }

    @Override
    public boolean guardsTransactionally() {
        return true;
    }
}
