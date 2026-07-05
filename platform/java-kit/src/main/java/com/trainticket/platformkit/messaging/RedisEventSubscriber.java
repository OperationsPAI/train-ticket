package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RedisEventSubscriber implements EventSubscriber {
    static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(RedisEventSubscriber.class);
    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    public RedisEventSubscriber(String redisUrl) {
        client = RedisClient.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl);
        connection = client.connect();
        streams = new LettuceRedisStreamOperations(connection);
        objectMapper = RedisEventPublisher.defaultMapper();
    }

    public RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper) {
        this.streams = Objects.requireNonNull(streams);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void subscribe(List<String> streamNames, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) throws SubscribeFailed {
        Objects.requireNonNull(streamNames, "streams are required"); Objects.requireNonNull(handler, "handler is required");
        if (streamNames.isEmpty()) throw new SubscribeFailed("at least one stream is required", null);
        for (String stream : streamNames) streams.createGroup(stream, group);
        if (!running.compareAndSet(false, true)) throw new SubscribeFailed("subscriber is already running", null);
        executor.submit(() -> poll(streamNames, group, consumerName, handler));
        executor.submit(() -> recoverLoop(streamNames, group, consumerName, handler));
    }

    public void recoverOnce(String stream, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) {
        for (RedisStreamOperations.StreamEntry message : streams.autoClaim(stream, group, consumerName)) handle(stream, group, message, handler, streams.deliveryCount(stream, group, message.id()));
    }

    private void poll(List<String> streamNames, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) {
        while (running.get()) {
            for (String stream : streamNames) for (RedisStreamOperations.StreamEntry message : streams.readGroup(stream, group, consumerName)) handle(stream, group, message, handler, 1);
        }
    }

    private void recoverLoop(List<String> streamNames, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) {
        while (running.get()) {
            try { Thread.sleep(60_000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            for (String stream : streamNames) recoverOnce(stream, group, consumerName, handler);
        }
    }

    private void handle(String stream, String group, RedisStreamOperations.StreamEntry message, Function<EventEnvelope, HandlerResult> handler, int deliveryAttempts) {
        String json = message.envelopeJson();
        if (json == null || json.isBlank()) { streams.moveToDlq(stream, "{}"); streams.ack(stream, group, message.id()); return; }
        if (deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) { streams.moveToDlq(stream, json); streams.ack(stream, group, message.id()); return; }
        EventEnvelope envelope;
        try { envelope = objectMapper.readValue(json, EventEnvelope.class); } catch (JsonProcessingException ex) { streams.moveToDlq(stream, json); streams.ack(stream, group, message.id()); return; }
        if (consumed.contains(envelope.eventId())) { streams.ack(stream, group, message.id()); return; }
        try {
            HandlerResult result = handler.apply(envelope);
            if (result instanceof HandlerResult.Success) { consumed.add(envelope.eventId()); streams.ack(stream, group, message.id()); }
            else if (result instanceof HandlerResult.FatalError) { streams.moveToDlq(stream, json); streams.ack(stream, group, message.id()); }
        } catch (RuntimeException ex) { log.warn("event handler failed for {}", envelope.eventId(), ex); }
    }

    @Override public void shutdown() { running.set(false); executor.shutdownNow(); try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } if (connection != null) connection.close(); if (client != null) client.shutdown(); }
}
