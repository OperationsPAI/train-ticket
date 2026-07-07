package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.application.ConsumedEventDeduplicator;
import com.trainticket.platformkit.persistence.ProcessedEventStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresConsumedEventDeduplicator extends ConsumedEventDeduplicator {
    private final ProcessedEventStore store;

    public PostgresConsumedEventDeduplicator(DataSource dataSource) {
        this.store = new ProcessedEventStore(dataSource);
    }

    @Override
    public boolean isProcessed(String eventId) {
        return store.isProcessed(eventId);
    }

    @Override
    public void recordProcessed(String eventId) {
        recordIfNew(eventId);
    }

    @Override
    public boolean recordIfNew(String eventId) {
        return store.recordIfNew(eventId, null);
    }
}
