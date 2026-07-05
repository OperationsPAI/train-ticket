package com.trainticket.travelerprofile.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.PublishFailedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "traveler-profile.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisEventPublisher implements EventPublisher {
    private static final String FIELD_ENVELOPE = "envelope";
    private static final long MAX_STREAM_LENGTH = 100_000L;

    private final ObjectMapper objectMapper;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisEventPublisher(
        ObjectMapper objectMapper,
        @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl
    ) {
        this.objectMapper = objectMapper;
        this.client = RedisClient.create(redisUrl);
        this.connection = client.connect();
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String stream = streamFor(envelope.producer());
        String serialized = serialize(envelope);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                RedisCommands<String, String> commands = connection.sync();
                commands.xadd(stream, XAddArgs.Builder.maxlen(MAX_STREAM_LENGTH).approximateTrimming(), Map.of(FIELD_ENVELOPE, serialized));
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                sleepBeforeRetry(attempt);
            }
        }
        throw new PublishFailedException("Failed to publish event envelope", lastFailure);
    }

    @PreDestroy
    public void close() {
        connection.close();
        client.shutdown();
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new PublishFailedException("Failed to serialize event envelope", ex);
        }
    }

    private static String streamFor(String producer) {
        return "events:" + producer;
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(50L * attempt).toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while retrying event publish", ex);
        }
    }
}
