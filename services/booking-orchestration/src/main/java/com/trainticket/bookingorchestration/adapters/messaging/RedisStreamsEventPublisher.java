package com.trainticket.bookingorchestration.adapters.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.bookingorchestration.application.EventPublisher;
import com.trainticket.bookingorchestration.application.PublishFailed;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisStreamsEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsEventPublisher.class);
    private static final long MAXLEN = 100_000L;
    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(100);

    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper objectMapper;

    public RedisStreamsEventPublisher(RedisClient redisClient) {
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public void publish(EventEnvelope envelope) {
        String stream = "events:" + envelope.producer();
        String envelopeJson;
        try {
            envelopeJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new PublishFailed("Failed to serialize event envelope", e);
        }

        RedisException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var args = XAddArgs.Builder.maxlen(MAXLEN);
                commands.xadd(stream, args, Map.of("envelope", envelopeJson));
                return;
            } catch (RedisException e) {
                lastException = e;
                log.warn("Failed to publish event to stream {} (attempt {}/{}): {}",
                    stream, attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepWithBackoff(attempt);
                }
            }
        }
        throw new PublishFailed(
            "Failed to publish event to stream " + stream + " after " + MAX_RETRIES + " attempts",
            lastException);
    }

    private void sleepWithBackoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF.multipliedBy(1L << (attempt - 1)).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailed("Interrupted during backoff", e);
        }
    }

    /**
     * Close only the connection — the RedisClient is shared and managed elsewhere.
     */
    public void shutdown() {
        if (connection != null) connection.close();
    }
}
