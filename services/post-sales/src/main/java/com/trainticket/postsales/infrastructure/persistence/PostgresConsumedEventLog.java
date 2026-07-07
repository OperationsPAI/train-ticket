package com.trainticket.postsales.infrastructure.persistence;

import com.trainticket.platformkit.persistence.ProcessedEventStore;
import com.trainticket.postsales.application.ConsumedEventLog;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresConsumedEventLog implements ConsumedEventLog {
    private final ProcessedEventStore store;
    public PostgresConsumedEventLog(DataSource dataSource) { this.store = new ProcessedEventStore(dataSource); }
    @Override public boolean recordIfFirstSeen(String eventId) { return store.recordIfNew(eventId, null); }
}
