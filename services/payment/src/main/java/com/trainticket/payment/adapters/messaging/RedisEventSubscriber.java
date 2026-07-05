package com.trainticket.payment.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.application.EventEnvelope;
import com.trainticket.payment.application.EventSubscriber;
import com.trainticket.payment.application.HandlerResult;
import com.trainticket.payment.application.SubscribeFailedException;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RedisEventSubscriber implements EventSubscriber, AutoCloseable {
    private static final int MAX_DELIVERY_ATTEMPTS = 5;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    RedisEventSubscriber(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this.connection = Objects.requireNonNull(connection, "connection is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        Objects.requireNonNull(streams, "streams are required");
        if (streams.isEmpty()) {
            throw new SubscribeFailedException("at least one stream is required", null);
        }
        createGroups(streams, group);
        running.set(true);
        executor.submit(() -> poll(streams, group, consumerName, handler));
    }

    private void createGroups(List<String> streams, String group) {
        for (String stream : streams) {
            try {
                connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), group, io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
            } catch (RedisBusyException ignored) {
                // Existing group is safe on restart.
            } catch (RuntimeException exception) {
                throw new SubscribeFailedException("consumer group could not be created", exception);
            }
        }
    }

    private void poll(List<String> streams, String group, String consumerName, EventHandler handler) {
        while (running.get()) {
            for (String stream : streams) {
                recover(stream, group, consumerName, handler);
                List<StreamMessage<String, String>> messages = connection.sync().xreadgroup(Consumer.from(group, consumerName), XReadArgs.Builder.block(Duration.ofSeconds(2)).count(10), XReadArgs.StreamOffset.lastConsumed(stream));
                for (StreamMessage<String, String> message : messages) {
                    handle(stream, group, message, handler, 1);
                }
            }
        }
    }

    private void recover(String stream, String group, String consumerName, EventHandler handler) {
        List<StreamMessage<String, String>> messages = connection.sync().xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumerName), Duration.ofSeconds(60), "0-0").count(100)).getMessages();
        for (StreamMessage<String, String> message : messages) {
            handle(stream, group, message, handler, MAX_DELIVERY_ATTEMPTS);
        }
    }

    private void handle(String stream, String group, StreamMessage<String, String> message, EventHandler handler, int deliveryAttempts) {
        String json = message.getBody().get("envelope");
        if (json == null || deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {
            moveToDlq(stream, json == null ? "{}" : json);
            ack(stream, group, message.getId());
            return;
        }
        EventEnvelope envelope = deserialize(json);
        HandlerResult result = handler.handle(envelope);
        if (result == HandlerResult.SUCCESS) {
            ack(stream, group, message.getId());
        } else if (result == HandlerResult.FATAL_FAILURE) {
            moveToDlq(stream, json);
            ack(stream, group, message.getId());
        }
    }

    private EventEnvelope deserialize(String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new SubscribeFailedException("event envelope could not be deserialized", exception);
        }
    }

    private void ack(String stream, String group, String id) {
        connection.sync().xack(stream, group, id);
    }

    private void moveToDlq(String stream, String json) {
        connection.sync().xadd(RedisStreamNames.dlqFor(stream), XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("envelope", json));
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
