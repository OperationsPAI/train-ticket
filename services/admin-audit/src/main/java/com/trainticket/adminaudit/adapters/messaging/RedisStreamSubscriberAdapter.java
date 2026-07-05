package com.trainticket.adminaudit.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.application.ports.SubscribeFailedException;
import io.lettuce.core.Consumer;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.Limit;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ADMIN_AUDIT_REDIS_ENABLED", havingValue = "true", matchIfMissing = true)
public class RedisStreamSubscriberAdapter implements EventSubscriber, AutoCloseable {
    private static final String FIELD = "envelope";
    private static final long MAX_LEN = 100_000L;
    private static final long BLOCK_MILLIS = 2_000L;
    private static final long MIN_IDLE_MILLIS = 60_000L;
    private static final long MAX_ATTEMPTS = 5L;

    private final ObjectMapper objectMapper;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RedisStreamSubscriberAdapter(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = RedisClient.create(RedisURI.create(redisUrl));
        this.connection = client.connect();
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler)
        throws SubscribeFailedException {
        RedisCommands<String, String> commands = connection.sync();
        try {
            for (String stream : streams) {
                createGroup(commands, stream, group);
            }
            running.set(true);
            executor.submit(() -> poll(streams, group, consumerName, handler));
            executor.submit(() -> recover(streams, group, consumerName, handler));
        } catch (RuntimeException exception) {
            throw new SubscribeFailedException("failed to start Redis stream subscriber", exception);
        }
    }

    private void poll(List<String> streams, String group, String consumerName, EventHandler handler) {
        RedisCommands<String, String> commands = connection.sync();
        while (running.get()) {
            for (String stream : streams) {
                List<io.lettuce.core.StreamMessage<String, String>> messages = commands.xreadgroup(
                    Consumer.from(group, consumerName),
                    XReadArgs.Builder.block(BLOCK_MILLIS).count(10),
                    XReadArgs.StreamOffset.lastConsumed(stream)
                );
                for (io.lettuce.core.StreamMessage<String, String> message : messages) {
                    process(commands, stream, group, message, handler, 1L);
                }
            }
        }
    }

    private void recover(List<String> streams, String group, String consumerName, EventHandler handler) {
        RedisCommands<String, String> commands = connection.sync();
        while (running.get()) {
            for (String stream : streams) {
                commands.xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumerName), MIN_IDLE_MILLIS, "0-0"))
                    .getMessages()
                    .forEach(message -> process(commands, stream, group, message, handler, deliveryCount(commands, stream, group, message.getId())));
            }
            sleep(MIN_IDLE_MILLIS);
        }
    }

    private void process(
        RedisCommands<String, String> commands,
        String stream,
        String group,
        io.lettuce.core.StreamMessage<String, String> message,
        EventHandler handler,
        long deliveryCount
    ) {
        String envelopeJson = message.getBody().get(FIELD);
        if (deliveryCount >= MAX_ATTEMPTS) {
            moveToDlq(commands, stream, envelopeJson);
            commands.xack(stream, group, message.getId());
            return;
        }
        try {
            HandlerResult result = handler.handle(objectMapper.readValue(envelopeJson, EventEnvelope.class));
            if (result == HandlerResult.SUCCESS) {
                commands.xack(stream, group, message.getId());
            } else if (result == HandlerResult.FATAL_FAILURE) {
                moveToDlq(commands, stream, envelopeJson);
                commands.xack(stream, group, message.getId());
            }
        } catch (JsonProcessingException exception) {
            moveToDlq(commands, stream, envelopeJson);
            commands.xack(stream, group, message.getId());
        }
    }

    private long deliveryCount(RedisCommands<String, String> commands, String stream, String group, String messageId) {
        return commands.xpending(stream, group, Range.create(messageId, messageId), Limit.from(1)).stream()
            .filter(pending -> pending.getId().equals(messageId))
            .findFirst()
            .map(pending -> pending.getRedeliveryCount())
            .orElse(1L);
    }

    private void moveToDlq(RedisCommands<String, String> commands, String stream, String envelopeJson) {
        commands.xadd(stream + ":dlq", XAddArgs.Builder.maxlen(MAX_LEN).approximateTrimming(), Map.of(FIELD, envelopeJson));
    }

    private void createGroup(RedisCommands<String, String> commands, String stream, String group) {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException ignored) {
            // Consumer group already exists.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        connection.close();
        client.shutdown();
    }
}
