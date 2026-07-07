package com.trainticket.travelerprofile.infrastructure.persistence;

import com.trainticket.platformkit.persistence.ProcessedEventStore;
import com.trainticket.travelerprofile.application.ConsumedEventLog;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresConsumedEventLog extends ConsumedEventLog {
    private final ProcessedEventStore store;

    public PostgresConsumedEventLog(DataSource dataSource) {
        this.store = new ProcessedEventStore(dataSource);
    }

    @Override
    public boolean recordIfNew(String eventId) {
        return store.recordIfNew(eventId, null);
    }

    @Override
    public void record(String eventId) {
        recordIfNew(eventId);
    }

    @Override
    public void discard(String eventId) {
        // A real Spring transaction rolls back the INSERT; do not delete committed rows.
    }

    @Override
    public boolean hasConsumed(String eventId) {
        return store.isProcessed(eventId);
    }
}
