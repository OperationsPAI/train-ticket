package com.trainticket.financesettlement.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.EventEnvelope;
import com.trainticket.financesettlement.application.EventSubscriber;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.HandlerResult;
import com.trainticket.financesettlement.application.SubscribeFailedException;
import io.lettuce.core.Consumer;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.Limit;
import io.lettuce.core.XPendingArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(StatefulRedisConnection.class)
public class RedisEventSubscriber implements EventSubscriber, SmartLifecycle {
    private static final String ENVELOPE_FIELD = "envelope";
    private static final int MAX_DELIVERIES = 5;
    private static final long MAX_STREAM_LENGTH = 100_000L;
    private static final Duration BLOCK = Duration.ofSeconds(2);
    private static final Duration CLAIM_IDLE = Duration.ofSeconds(60);

    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;
    private final RedisMessagingProperties properties;
    private final FinanceSettlementEventHandler defaultHandler;
    private final Set<String> adapterDedup = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public RedisEventSubscriber(
        StatefulRedisConnection<String, String> connection,
        ObjectMapper objectMapper,
        RedisMessagingProperties properties,
        FinanceSettlementEventHandler defaultHandler
    ) {
        this.connection = connection;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.defaultHandler = defaultHandler;
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        try {
            streams.forEach(stream -> createGroup(stream, group));
            running.set(true);
            executor = Executors.newFixedThreadPool(Math.max(1, streams.size()));
            streams.forEach(stream -> executor.submit(() -> poll(stream, group, consumerName, handler)));
        } catch (RuntimeException ex) {
            throw new SubscribeFailedException("failed to subscribe to event streams", ex);
        }
    }

    @Override
    public void start() {
        subscribe(RedisMessagingProperties.SUBSCRIBED_STREAMS, RedisMessagingProperties.CONSUMER_GROUP, properties.consumerName(), defaultHandler);
    }

    @Override
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void createGroup(String stream, String group) {
        try {
            connection.sync().xgroupCreate(StreamOffset.from(stream, "$"), group, new XGroupCreateArgs().mkstream(true));
        } catch (RedisBusyException ignored) {
            // Existing group is expected on restart.
        }
    }

    private void poll(String stream, String group, String consumerName, EventHandler handler) {
        Consumer<String> consumer = Consumer.from(group, consumerName);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            recoverPending(stream, group, consumer, handler);
            List<StreamMessage<String, String>> messages = connection.sync().xreadgroup(
                consumer,
                new XReadArgs().block(BLOCK).count(10),
                StreamOffset.lastConsumed(stream)
            );
            for (StreamMessage<String, String> message : messages) {
                handle(stream, group, message, handler, false);
            }
        }
    }

    private void recoverPending(String stream, String group, Consumer<String> consumer, EventHandler handler) {
        ClaimedMessages<String, String> claimed = connection.sync().xautoclaim(
            stream,
            XAutoClaimArgs.Builder.xautoclaim(consumer, CLAIM_IDLE, "0").count(100)
        );
        for (StreamMessage<String, String> message : claimed.getMessages()) {
            handle(stream, group, message, handler, true);
        }
    }

    private void handle(String stream, String group, StreamMessage<String, String> message, EventHandler handler, boolean recovered) {
        String serialized = message.getBody().get(ENVELOPE_FIELD);
        if (serialized == null) {
            deadLetter(stream, serialized == null ? "{}" : serialized);
            ack(stream, group, message.getId());
            return;
        }
        if (recovered && deliveryCount(stream, group, message.getId()) >= MAX_DELIVERIES) {
            deadLetter(stream, serialized);
            ack(stream, group, message.getId());
            return;
        }
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(serialized, EventEnvelope.class);
        } catch (JsonProcessingException ex) {
            deadLetter(stream, serialized);
            ack(stream, group, message.getId());
            return;
        }
        if (!adapterDedup.add(envelope.eventId())) {
            ack(stream, group, message.getId());
            return;
        }
        HandlerResult result = handler.handle(envelope);
        if (result == HandlerResult.SUCCESS) {
            ack(stream, group, message.getId());
        } else if (result == HandlerResult.FATAL_FAILURE) {
            deadLetter(stream, serialized);
            ack(stream, group, message.getId());
        } else {
            adapterDedup.remove(envelope.eventId());
        }
    }

    private long deliveryCount(String stream, String group, String messageId) {
        List<PendingMessage> pending = connection.sync().xpending(stream, new XPendingArgs<String>()
            .group(group)
            .range(Range.create(messageId, messageId))
            .limit(Limit.from(1)));
        return pending.isEmpty() ? 1 : pending.getFirst().getRedeliveryCount() + 1;
    }

    private void deadLetter(String stream, String serialized) {
        connection.sync().xadd(stream + ":dlq", new XAddArgs().maxlen(MAX_STREAM_LENGTH).approximateTrimming(), Map.of(ENVELOPE_FIELD, serialized));
    }

    private void ack(String stream, String group, String messageId) {
        connection.sync().xack(stream, group, messageId);
    }
}
