package com.trainticket.travelerprofile.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.travelerprofile.application.EventEnvelope;
import com.trainticket.travelerprofile.application.EventSubscriber;
import com.trainticket.travelerprofile.application.SubscribeFailedException;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.Range;
import io.lettuce.core.Limit;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "traveler-profile.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisEventSubscriber implements EventSubscriber, AutoCloseable {
    private static final String FIELD_ENVELOPE = "envelope";
    private static final long MAX_STREAM_LENGTH = 100_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final ObjectMapper objectMapper;
    private final RedisEventSubscriberConnectionFactory connectionFactory;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RedisEventSubscriber(ObjectMapper objectMapper, @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl) {
        this.objectMapper = objectMapper;
        this.connectionFactory = new RedisEventSubscriberConnectionFactory(redisUrl);
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        try {
            StatefulRedisConnection<String, String> connection = connectionFactory.connect();
            RedisCommands<String, String> commands = connection.sync();
            for (String stream : streams) {
                createGroup(commands, stream, group);
            }
            executor.submit(() -> poll(commands, streams, group, consumerName, handler));
            executor.submit(() -> recover(commands, streams, group, consumerName, handler));
        } catch (RuntimeException ex) {
            throw new SubscribeFailedException("Failed to start Redis Streams subscriber", ex);
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        connectionFactory.close();
    }

    private void poll(RedisCommands<String, String> commands, List<String> streams, String group, String consumerName, EventHandler handler) {
        while (running.get()) {
            List<StreamMessage<String, String>> messages = new ArrayList<>();
            for (String stream : streams) {
                messages.addAll(commands.xreadgroup(
                    Consumer.from(group, consumerName),
                    XReadArgs.Builder.block(2000).count(10),
                    io.lettuce.core.XReadArgs.StreamOffset.lastConsumed(stream)
                ));
            }
            for (StreamMessage<String, String> message : messages) {
                handleMessage(commands, message.getStream(), group, message.getId(), message.getBody(), handler, 1);
            }
        }
    }

    private void recover(RedisCommands<String, String> commands, List<String> streams, String group, String consumerName, EventHandler handler) {
        while (running.get()) {
            for (String stream : streams) {
                List<StreamMessage<String, String>> claimed = commands.xautoclaim(stream, new XAutoClaimArgs<String>()
                    .consumer(Consumer.from(group, consumerName))
                    .minIdleTime(60_000)
                    .startId("0-0")
                    .count(100)).getMessages();
                for (StreamMessage<String, String> message : claimed) {
                    handleMessage(commands, stream, group, message.getId(), message.getBody(), handler, 1);
                }
            }
            sleep(60_000);
        }
    }

    private void handleMessage(
        RedisCommands<String, String> commands,
        String stream,
        String group,
        String messageId,
        Map<String, String> body,
        EventHandler handler,
        int attempt
    ) {
        int observedAttempts = Math.max(attempt, deliveryAttempts(commands, stream, group, messageId));
        String serialized = body.get(FIELD_ENVELOPE);
        try {
            EventEnvelope envelope = objectMapper.readValue(serialized, EventEnvelope.class);
            if (observedAttempts >= MAX_ATTEMPTS) {
                moveToDlq(commands, stream, serialized);
                commands.xack(stream, group, messageId);
                return;
            }
            HandlerResult result = handler.handle(envelope);
            if (result == HandlerResult.SUCCESS) {
                commands.xack(stream, group, messageId);
            } else if (result == HandlerResult.FATAL_FAILURE) {
                moveToDlq(commands, stream, serialized);
                commands.xack(stream, group, messageId);
            }
        } catch (Exception ex) {
            moveToDlq(commands, stream, serialized);
            commands.xack(stream, group, messageId);
        }
    }

    private int deliveryAttempts(RedisCommands<String, String> commands, String stream, String group, String messageId) {
        return commands.xpending(stream, group, Range.create(messageId, messageId), Limit.from(1))
            .stream()
            .findFirst()
            .map(message -> (int) message.getRedeliveryCount() + 1)
            .orElse(1);
    }

    private void createGroup(RedisCommands<String, String> commands, String stream, String group) {
        try {
            commands.xgroupCreate(io.lettuce.core.XReadArgs.StreamOffset.latest(stream), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RuntimeException ex) {
            if (!String.valueOf(ex.getMessage()).contains("BUSYGROUP")) {
                throw ex;
            }
        }
    }

    private void moveToDlq(RedisCommands<String, String> commands, String stream, String serialized) {
        commands.xadd(stream + ":dlq", XAddArgs.Builder.maxlen(MAX_STREAM_LENGTH).approximateTrimming(), Map.of(FIELD_ENVELOPE, serialized));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static List<String> travelerProfileSubscriptions() {
        return new ArrayList<>();
    }
}
