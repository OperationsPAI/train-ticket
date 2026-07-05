package com.trainticket.payment.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.application.EventPublisher;
import com.trainticket.payment.application.PublishFailedException;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class RedisEventPublisher implements EventPublisher {
    private static final int MAX_ATTEMPTS = 3;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;

    RedisEventPublisher(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this.connection = Objects.requireNonNull(connection, "connection is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String json = serialize(envelope);
        String stream = RedisStreamNames.streamForProducer(envelope.producer());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                connection.sync().xadd(stream, XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("envelope", json));
                return;
            } catch (RuntimeException exception) {
                last = exception;
                backoff(attempt);
            }
        }
        throw new PublishFailedException("event was not published", last);
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new PublishFailedException("event envelope could not be serialized", exception);
        }
    }

    private static void backoff(int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(50L * attempt).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("publish retry interrupted", exception);
        }
    }
}
