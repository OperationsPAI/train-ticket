package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;

public class RedisEventPublisher implements EventPublisher, AutoCloseable {
    private static final int MAX_ATTEMPTS = 3;

    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final AutoCloseable closeable;

    public RedisEventPublisher(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this(new LettuceRedisStreamOperations(connection), objectMapper, connection::close);
    }

    public static RedisEventPublisher fromUrl(String redisUrl, ObjectMapper objectMapper) {
        RedisClient client = RedisClient.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl);
        StatefulRedisConnection<String, String> connection = client.connect();
        return new RedisEventPublisher(new LettuceRedisStreamOperations(connection), objectMapper, () -> {
            connection.close();
            client.shutdown();
        });
    }

    RedisEventPublisher(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable) {
        this.streams = Objects.requireNonNull(streams, "streams are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.closeable = closeable;
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String json = serialize(envelope);
        String stream = RedisStreamNames.streamForProducer(envelope.producer());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                streams.publish(stream, json);
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

    @Override
    public void close() throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }
}
