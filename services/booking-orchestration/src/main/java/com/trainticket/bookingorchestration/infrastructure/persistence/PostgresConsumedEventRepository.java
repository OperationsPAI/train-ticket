package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.trainticket.bookingorchestration.application.ConsumedEventRepository;
import com.trainticket.platformkit.persistence.ProcessedEventStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresConsumedEventRepository implements ConsumedEventRepository {
    private final ProcessedEventStore store;

    public PostgresConsumedEventRepository(DataSource dataSource) {
        this.store = new ProcessedEventStore(dataSource);
    }

    @Override
    public boolean recordIfNew(String eventId, String stream) {
        return store.recordIfNew(eventId, stream);
    }
}
