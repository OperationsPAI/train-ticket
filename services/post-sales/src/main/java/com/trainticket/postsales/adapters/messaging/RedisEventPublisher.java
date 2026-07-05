package com.trainticket.postsales.adapters.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.postsales.application.EventPublisher;
import com.trainticket.postsales.application.PublishFailedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true")
@Primary
public class RedisEventPublisher implements EventPublisher, AutoCloseable {
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAXLEN = 100_000L;

    private final ObjectMapper objectMapper;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        this.objectMapper = objectMapper;
        this.client = RedisClient.create(properties.redisUrl());
        this.connection = client.connect();
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String stream = RedisStreamNames.forProducer(envelope.producer());
        String json = serialize(envelope);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                RedisCommands<String, String> commands = connection.sync();
                commands.xadd(stream, XAddArgs.Builder.maxlen(MAXLEN).approximateTrimming(), Map.of("envelope", json));
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                backoff(attempt);
            }
        }
        throw new PublishFailedException("failed to publish event envelope", lastFailure);
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException ex) {
            throw new PublishFailedException("failed to serialize event envelope", ex);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(50L * attempt).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
