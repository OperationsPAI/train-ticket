package com.trainticket.platformkit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.RedisStreamNames;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class OutboxAppender {
    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;
    private final Duration replayRetention;
    private final Set<String> replayEventTypes;

    public OutboxAppender(javax.sql.DataSource dataSource, ObjectMapper objectMapper) {
        this(new JdbcTemplate(dataSource), objectMapper, null, Set.of());
    }

    public OutboxAppender(javax.sql.DataSource dataSource, ObjectMapper objectMapper, Duration replayRetention) {
        this(new JdbcTemplate(dataSource), objectMapper, replayRetention, Set.of());
    }

    public OutboxAppender(javax.sql.DataSource dataSource, ObjectMapper objectMapper, Duration replayRetention, Set<String> replayEventTypes) {
        this(new JdbcTemplate(dataSource), objectMapper, replayRetention, replayEventTypes);
    }

    public OutboxAppender(JdbcOperations jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, null, Set.of());
    }

    public OutboxAppender(JdbcOperations jdbc, ObjectMapper objectMapper, Duration replayRetention) {
        this(jdbc, objectMapper, replayRetention, Set.of());
    }

    public OutboxAppender(JdbcOperations jdbc, ObjectMapper objectMapper, Duration replayRetention, Set<String> replayEventTypes) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        if (replayRetention != null && (replayRetention.isZero() || replayRetention.isNegative())) {
            throw new IllegalArgumentException("replayRetention must be positive");
        }
        this.replayRetention = replayRetention;
        this.replayEventTypes = Set.copyOf(Objects.requireNonNull(replayEventTypes, "replayEventTypes is required"));
    }

    public void append(EventEnvelope envelope) {
        append(RedisStreamNames.streamForProducer(envelope.producer()), envelope, toEnvelopeJson(envelope));
    }

    public void append(EventEnvelope envelope, String envelopeJson) {
        append(RedisStreamNames.streamForProducer(envelope.producer()), envelope, envelopeJson);
    }

    public void append(String stream, EventEnvelope envelope, String envelopeJson) {
        EventEnvelope requiredEnvelope = Objects.requireNonNull(envelope, "envelope is required");
        String requiredStream = requireText(stream, "stream");
        String requiredJson = requireText(envelopeJson, "envelopeJson");
        jdbc.update(
            "INSERT INTO outbox(event_id, stream, envelope) VALUES (?, ?, ?::jsonb) ON CONFLICT (event_id) DO NOTHING",
            requiredEnvelope.eventId(),
            requiredStream,
            requiredJson
        );
        if (replayRetention != null && (replayEventTypes.isEmpty() || replayEventTypes.contains(requiredEnvelope.eventType()))) {
            int inserted = jdbc.update(
                "INSERT INTO event_replay_archive(event_id, event_type, stream, envelope, retained_until) "
                    + "VALUES (?, ?, ?, ?::jsonb, now() + (? * interval '1 millisecond')) "
                    + "ON CONFLICT (event_id) DO NOTHING",
                requiredEnvelope.eventId(), requiredEnvelope.eventType(), requiredStream, requiredJson, replayRetention.toMillis()
            );
            if (inserted == 0) {
                Integer matching = jdbc.queryForObject(
                    "SELECT count(*) FROM event_replay_archive WHERE event_id=? AND event_type=? AND stream=? AND envelope=?::jsonb",
                    Integer.class, requiredEnvelope.eventId(), requiredEnvelope.eventType(), requiredStream, requiredJson
                );
                if (matching == null || matching != 1) {
                    throw new IllegalStateException("event replay identity reused with different event material: " + requiredEnvelope.eventId());
                }
            }
        }
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
