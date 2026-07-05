package com.trainticket.postsales.adapters.messaging;

import com.trainticket.postsales.application.EventEnvelope;
import com.trainticket.postsales.application.EventSubscriber;
import com.trainticket.postsales.application.SubscribeFailedException;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true")
public class RedisEventSubscriber implements EventSubscriber, AutoCloseable {
    private static final long MAXLEN = 100_000L;
    private static final long BLOCK_MILLIS = 2_000L;
    private static final long MIN_IDLE_MILLIS = 60_000L;
    private static final int MAX_DELIVERIES = 5;

    private final ObjectMapper objectMapper;
    private final RedisMessagingProperties properties;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public RedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = RedisClient.create(properties.redisUrl());
        this.connection = client.connect();
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        try {
            RedisCommands<String, String> commands = connection.sync();
            for (String stream : streams) {
                createGroup(commands, stream, group);
            }
            running.set(true);
            executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> poll(commands, streams, group, consumerName, handler));
        } catch (RuntimeException ex) {
            throw new SubscribeFailedException("failed to subscribe to event streams", ex);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        connection.close();
        client.shutdown();
    }

    private void poll(RedisCommands<String, String> commands, List<String> streams, String group, String consumerName, EventHandler handler) {
        while (running.get()) {
            for (String stream : streams) {
                recover(commands, stream, group, consumerName, handler);
                List<StreamMessage<String, String>> messages = commands.xreadgroup(
                    Consumer.from(group, consumerName),
                    XReadArgs.Builder.block(BLOCK_MILLIS).count(10),
                    XReadArgs.StreamOffset.lastConsumed(stream)
                );
                for (StreamMessage<String, String> message : messages) {
                    handle(commands, stream, group, message, handler, 1);
                }
            }
        }
    }

    private void recover(RedisCommands<String, String> commands, String stream, String group, String consumerName, EventHandler handler) {
        XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
            .consumer(Consumer.from(group, consumerName))
            .minIdleTime(MIN_IDLE_MILLIS)
            .startId("0-0")
            .count(100);
        ClaimedMessages<String, String> claimed = commands.xautoclaim(stream, args);
        if (claimed == null) {
            return;
        }
        for (StreamMessage<String, String> message : claimed.getMessages()) {
            handle(commands, stream, group, message, handler, 2);
        }
    }

    private void handle(RedisCommands<String, String> commands, String stream, String group, StreamMessage<String, String> message, EventHandler handler, int deliveryCount) {
        String envelopeJson = message.getBody().get("envelope");
        try {
            EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
            HandlerResult result = deliveryCount >= MAX_DELIVERIES ? HandlerResult.FATAL_FAILURE : handler.handle(envelope);
            if (result == HandlerResult.SUCCESS) {
                commands.xack(stream, group, message.getId());
            } else if (result == HandlerResult.FATAL_FAILURE) {
                commands.xadd(RedisStreamNames.dlq(stream), XAddArgs.Builder.maxlen(MAXLEN).approximateTrimming(), Map.of("envelope", envelopeJson));
                commands.xack(stream, group, message.getId());
            }
        } catch (Exception fatal) {
            commands.xadd(RedisStreamNames.dlq(stream), XAddArgs.Builder.maxlen(MAXLEN).approximateTrimming(), Map.of("envelope", envelopeJson == null ? "" : envelopeJson));
            commands.xack(stream, group, message.getId());
        }
    }

    private void createGroup(RedisCommands<String, String> commands, String stream, String group) {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.latest(stream), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException alreadyExists) {
            // Safe on restart.
        }
    }
}
