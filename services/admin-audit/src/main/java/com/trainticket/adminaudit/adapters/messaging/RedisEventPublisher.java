package com.trainticket.adminaudit.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import com.trainticket.adminaudit.application.ports.PublishFailedException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ADMIN_AUDIT_REDIS_ENABLED", havingValue = "true", matchIfMissing = true)
public class RedisEventPublisher implements EventPublisher, AutoCloseable {
    private static final String FIELD = "envelope";
    private static final long MAX_LEN = 100_000L;

    private final ObjectMapper objectMapper;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisEventPublisher(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = RedisClient.create(RedisURI.create(redisUrl));
        this.connection = client.connect();
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new PublishFailedException("failed to serialize event envelope", exception);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                RedisCommands<String, String> commands = connection.sync();
                commands.xadd(streamFor(envelope.producer()), XAddArgs.Builder.maxlen(MAX_LEN).approximateTrimming(), Map.of(FIELD, body));
                return;
            } catch (RuntimeException exception) {
                last = exception;
                sleep(attempt);
            }
        }
        throw new PublishFailedException("failed to publish event envelope", last);
    }

    private static String streamFor(String producer) {
        return "events:" + producer;
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(50L * attempt).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("interrupted while retrying publish", interrupted);
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
