package com.trainticket.platformkit.persistence;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

class ProcessedEventStoreSqlTest {
    @Test
    void isProcessedQueriesProcessedEventsByEventId() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)",
            Boolean.class,
            "evt-1"
        )).thenReturn(true);

        new ProcessedEventStore(jdbc).isProcessed("evt-1");

        verify(jdbc).queryForObject(
            "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)",
            Boolean.class,
            "evt-1"
        );
    }
}
