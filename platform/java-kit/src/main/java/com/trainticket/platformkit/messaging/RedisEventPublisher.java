package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RedisEventPublisher implements EventPublisher, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);
    private static final int MAX_RETRIES = 3;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;

    public RedisEventPublisher(String redisUrl) {
        this(RedisClient.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl), defaultMapper());
    }

    RedisEventPublisher(RedisClient client, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client);
        this.connection = client.connect();
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailed {
        Objects.requireNonNull(envelope, "envelope is required");
        String stream = "events:" + envelope.producer();
        String json = serialize(envelope);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                connection.async().xadd(stream, XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("envelope", json)).get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception ex) {
                last = ex;
                log.warn("Failed to publish event {} to {} (attempt {}/{})", envelope.eventId(), stream, attempt, MAX_RETRIES);
            }
        }
        throw new PublishFailed("Failed to publish event " + envelope.eventId(), last);
    }

    String serialize(EventEnvelope envelope) {
        try { return objectMapper.writeValueAsString(envelope); } catch (JsonProcessingException ex) { throw new PublishFailed("Failed to serialize event envelope", ex); }
    }

    @Override public void close() { shutdown(); }
    public void shutdown() { connection.close(); client.shutdown(); }

    static ObjectMapper defaultMapper() { return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL); }
}
