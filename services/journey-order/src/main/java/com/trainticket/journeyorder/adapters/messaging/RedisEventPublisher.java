package com.trainticket.journeyorder.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.domain.EventEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Streams implementation of EventPublisher.
 * Lives ONLY in the adapters/messaging module — no Redis types leak into domain code.
 */
public class RedisEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 100;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;
    private final ObjectMapper objectMapper;

    public RedisEventPublisher(String redisUrl) {
        this.redisClient = RedisClient.create(redisUrl);
        this.connection = redisClient.connect();
        this.async = connection.async();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    RedisEventPublisher(RedisClient redisClient, StatefulRedisConnection<String, String> connection) {
        this.redisClient = redisClient;
        this.connection = connection;
        this.async = connection.async();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailed {
        String stream = "events:" + envelope.producer();
        String json = serialize(envelope);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                XAddArgs args = new XAddArgs().maxlen(100_000).approximateTrimming();
                RedisFuture<String> future = async.xadd(stream, args, Map.of("envelope", json));
                future.get(5, TimeUnit.SECONDS);
                log.debug("Published event {} to stream {}", envelope.eventId(), stream);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Failed to publish event {} (attempt {}/{}): {}", envelope.eventId(), attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PublishFailed("Interrupted during backoff", ie);
                    }
                }
            }
        }
        throw new PublishFailed("Failed to publish event " + envelope.eventId() + " after " + MAX_RETRIES + " attempts", lastError);
    }

    public void shutdown() {
        try {
            connection.close();
            redisClient.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down RedisEventPublisher", e);
        }
    }

    private String serialize(EventEnvelope envelope) {
        try {
            Map<String, Object> map = Map.of(
                "eventId", envelope.eventId(),
                "eventType", envelope.eventType(),
                "schemaVersion", envelope.schemaVersion(),
                "producer", envelope.producer(),
                "causationId", envelope.causationId(),
                "correlationId", envelope.correlationId(),
                "occurredAt", envelope.occurredAt().toString(),
                "payload", envelope.payload()
            );
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize EventEnvelope", e);
        }
    }
}
