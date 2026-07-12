package com.trainticket.platformkit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.RedisStreamNames;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class OutboxAppender {
    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;

    public OutboxAppender(javax.sql.DataSource dataSource, ObjectMapper objectMapper) {
        this(new JdbcTemplate(dataSource), objectMapper);
    }

    public OutboxAppender(JdbcOperations jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    public void append(EventEnvelope envelope) {
        append(RedisStreamNames.streamForProducer(envelope.producer()), envelope, toEnvelopeJson(envelope));
    }

    public void append(EventEnvelope envelope, String envelopeJson) {
        append(RedisStreamNames.streamForProducer(envelope.producer()), envelope, envelopeJson);
    }

    public void append(String stream, EventEnvelope envelope, String envelopeJson) {
        jdbc.update(
            "INSERT INTO outbox(event_id, stream, envelope) VALUES (?, ?, ?::jsonb) ON CONFLICT (event_id) DO NOTHING",
            Objects.requireNonNull(envelope, "envelope is required").eventId(),
            requireText(stream, "stream"),
            requireText(envelopeJson, "envelopeJson")
        );
    }

    private String toEnvelopeJson(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(envelope, "envelope is required"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event envelope could not be serialized", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
