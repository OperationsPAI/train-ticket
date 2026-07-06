package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RedisEventSubscriber implements EventSubscriber {
    public static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final long POLL_FAILURE_BACKOFF_MILLIS = 1_000;

    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AutoCloseable closeable;
    private final ConsumedEventStore consumedEvents;

    public RedisEventSubscriber(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this(new LettuceRedisStreamOperations(connection), objectMapper, connection::close);
    }

    public static RedisEventSubscriber fromUrl(String redisUrl, ObjectMapper objectMapper) {
        RedisClient client = RedisClient.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl);
        StatefulRedisConnection<String, String> connection = client.connect();
        return new RedisEventSubscriber(new LettuceRedisStreamOperations(connection), objectMapper, () -> {
            connection.close();
            client.shutdown();
        });
    }

    RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable) {
        this(streams, objectMapper, closeable, new InMemoryConsumedEventStore());
    }

    RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable, ConsumedEventStore consumedEvents) {
        this.streams = Objects.requireNonNull(streams, "streams are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.closeable = closeable;
        this.consumedEvents = Objects.requireNonNull(consumedEvents, "consumedEvents is required");
        this.executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void subscribe(List<String> streamNames, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        Objects.requireNonNull(streamNames, "streams are required");
        Objects.requireNonNull(handler, "handler is required");
        if (streamNames.isEmpty()) {
            throw new SubscribeFailedException("at least one stream is required", null);
        }
        createGroups(streamNames, group);
        running.set(true);
        executor.submit(() -> poll(streamNames, group, consumerName, handler));
    }

    public void recoverOnce(String stream, String group, String consumerName, EventHandler handler) {
        recover(stream, group, consumerName, handler);
    }

    private void createGroups(List<String> streamNames, String group) {
        for (String stream : streamNames) {
            try {
                streams.createGroup(stream, group);
            } catch (RuntimeException exception) {
                throw new SubscribeFailedException("consumer group could not be created", exception);
            }
        }
    }

    private void poll(List<String> streamNames, String group, String consumerName, EventHandler handler) {
        while (running.get()) {
            for (String stream : streamNames) {
                try {
                    recover(stream, group, consumerName, handler);
                    for (RedisStreamOperations.StreamEntry message : streams.readGroup(stream, group, consumerName)) {
                        handle(stream, group, message, handler, streams.deliveryCount(stream, group, message.id()));
                    }
                } catch (RuntimeException exception) {
                    // Keep the subscriber alive; messages read but not acked remain in the Redis PEL for recovery/DLQ policy.
                    // A non-persistent Redis loses consumer groups on restart: NOGROUP
                    // means "recreate the group and carry on". Anything else backs off
                    // so a dead connection never turns this loop into a hot spin.
                    if (isNoGroup(exception)) {
                        try {
                            streams.createGroup(stream, group);
                        } catch (RuntimeException ignored) {
                            // Redis still down; the backoff below paces the retry.
                        }
                    }
                    sleepQuietly(POLL_FAILURE_BACKOFF_MILLIS);
                }
            }
        }
    }

    private void recover(String stream, String group, String consumerName, EventHandler handler) {
        for (RedisStreamOperations.StreamEntry message : streams.autoClaim(stream, group, consumerName)) {
            handle(stream, group, message, handler, streams.deliveryCount(stream, group, message.id()));
        }
    }

    private void handle(String stream, String group, RedisStreamOperations.StreamEntry message, EventHandler handler, int deliveryAttempts) {
        String json = message.envelopeJson();
        if (json == null) {
            streams.moveToDlq(stream, "{}");
            streams.ack(stream, group, message.id());
            return;
        }
        if (deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {
            streams.moveToDlq(stream, json);
            streams.ack(stream, group, message.id());
            return;
        }
        EventEnvelope envelope;
        try {
            envelope = deserialize(json);
        } catch (SubscribeFailedException exception) {
            streams.moveToDlq(stream, json);
            streams.ack(stream, group, message.id());
            return;
        }
        if (consumedEvents.alreadyConsumed(group, envelope.eventId())) {
            streams.ack(stream, group, message.id());
            return;
        }
        HandlerResult result;
        try {
            result = handler.handle(envelope);
        } catch (RuntimeException exception) {
            return;
        }
        if (result == HandlerResult.SUCCESS) {
            consumedEvents.recordConsumed(group, envelope.eventId());
            streams.ack(stream, group, message.id());
        } else if (result == HandlerResult.FATAL_FAILURE) {
            streams.moveToDlq(stream, json);
            streams.ack(stream, group, message.id());
        }
    }

    private static boolean isNoGroup(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private EventEnvelope deserialize(String json) {
        try {
            return objectMapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new SubscribeFailedException("event envelope could not be deserialized", exception);
        }
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
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new SubscribeFailedException("subscriber could not close", exception);
            }
        }
    }
}
