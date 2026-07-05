package com.trainticket.payment.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.application.EventSubscriber;
import com.trainticket.payment.application.HandlerResult;
import com.trainticket.payment.application.SubscribeFailedException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RedisStreamSubscriberAdapter implements EventSubscriber, AutoCloseable {
    static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    RedisStreamSubscriberAdapter(RedisStreamOperations streams, ObjectMapper objectMapper) {
        this.streams = Objects.requireNonNull(streams, "streams are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
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

    void recoverOnce(String stream, String group, String consumerName, EventHandler handler) {
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
                recover(stream, group, consumerName, handler);
                for (RedisStreamOperations.StreamEntry message : streams.readGroup(stream, group, consumerName)) {
                    handle(stream, group, message, handler, 1);
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
        EventEnvelope envelope = deserialize(json);
        HandlerResult result = handler.handle(envelope);
        if (result == HandlerResult.SUCCESS) {
            streams.ack(stream, group, message.id());
        } else if (result == HandlerResult.FATAL_FAILURE) {
            streams.moveToDlq(stream, json);
            streams.ack(stream, group, message.id());
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
    }
}
