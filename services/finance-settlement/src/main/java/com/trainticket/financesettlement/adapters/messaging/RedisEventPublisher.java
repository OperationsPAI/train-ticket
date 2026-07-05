package com.trainticket.financesettlement.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.financesettlement.application.EventPublisher;
import com.trainticket.financesettlement.application.PublishFailedException;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(StatefulRedisConnection.class)
public class RedisEventPublisher implements EventPublisher {
    private static final String STREAM_PREFIX = "events:";
    private static final String ENVELOPE_FIELD = "envelope";
    private static final long MAX_STREAM_LENGTH = 100_000L;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;

    public RedisEventPublisher(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this.connection = connection;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        String stream = STREAM_PREFIX + envelope.producer();
        String serialized = serialize(envelope);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                connection.sync().xadd(
                    stream,
                    new XAddArgs().maxlen(MAX_STREAM_LENGTH).approximateTrimming(),
                    Map.of(ENVELOPE_FIELD, serialized)
                );
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                backoff(attempt);
            }
        }
        throw new PublishFailedException("failed to publish event envelope", lastFailure);
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new PublishFailedException("failed to serialize event envelope", ex);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("interrupted while publishing event envelope", ex);
        }
    }
}
