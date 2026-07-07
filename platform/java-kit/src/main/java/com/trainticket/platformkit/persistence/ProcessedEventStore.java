package com.trainticket.platformkit.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class ProcessedEventStore {
    private final JdbcOperations jdbc;

    public ProcessedEventStore(javax.sql.DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public ProcessedEventStore(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    public boolean recordIfNew(String eventId, String stream) {
        int inserted = jdbc.update(
            "INSERT INTO processed_events(event_id, stream) VALUES (?, ?) ON CONFLICT DO NOTHING",
            requireText(eventId, "eventId"),
            stream
        );
        return inserted == 1;
    }

    public boolean isProcessed(String eventId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)",
            Boolean.class,
            requireText(eventId, "eventId")
        );
        return Boolean.TRUE.equals(exists);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
