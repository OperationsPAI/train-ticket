package com.trainticket.journeyorder.adapters.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.domain.EventEnvelope;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Streams implementation of EventSubscriber.
 * Lives ONLY in the adapters/messaging module — no Redis types leak into domain code.
 */
public class RedisEventSubscriber implements EventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisEventSubscriber.class);
    private static final long RECOVERY_INTERVAL_MS = 60_000;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> dedupCache = new HashSet<>();
    private Thread pollThread;
    private Thread recoveryThread;

    public RedisEventSubscriber(String redisUrl) {
        this.redisClient = RedisClient.create(redisUrl);
        this.connection = redisClient.connect();
        this.async = connection.async();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    RedisEventSubscriber(RedisClient redisClient, StatefulRedisConnection<String, String> connection) {
        this.redisClient = redisClient;
        this.connection = connection;
        this.async = connection.async();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName,
                          Function<EventEnvelope, HandlerResult> handler) throws SubscribeFailed {
        if (!running.compareAndSet(false, true)) {
            throw new SubscribeFailed("Subscriber is already running", null);
        }

        for (String stream : streams) {
            try {
                XGroupCreateArgs args = new XGroupCreateArgs().mkstream(true);
                async.xgroupCreate(XReadArgs.StreamOffset.last(stream), group, args).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Consumer group may already exist for {}: {}", stream, e.getMessage());
            }
        }

        pollThread = new Thread(() -> pollingLoop(streams, group, consumerName, handler), "redis-subscriber-poll");
        pollThread.setDaemon(true);
        pollThread.start();

        recoveryThread = new Thread(() -> recoveryLoop(streams, group, consumerName, handler), "redis-subscriber-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    @Override
    public void shutdown() {
        running.set(false);
        if (pollThread != null) pollThread.interrupt();
        if (recoveryThread != null) recoveryThread.interrupt();
        try {
            connection.close();
            redisClient.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down RedisEventSubscriber", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void pollingLoop(List<String> streams, String group, String consumerName,
                             Function<EventEnvelope, HandlerResult> handler) {
        XReadArgs readArgs = new XReadArgs()
            .block(Duration.ofSeconds(2))
            .count(10);

        var streamOffsets = streams.stream()
            .map(s -> XReadArgs.StreamOffset.lastConsumed(s))
            .toArray(XReadArgs.StreamOffset[]::new);

        Consumer<String> consumer = Consumer.from(group, consumerName);

        while (running.get()) {
            try {
                var future = async.xreadgroup(consumer, readArgs, streamOffsets);
                List<StreamMessage<String, String>> messages = (List<StreamMessage<String, String>>)
                    (Object) future.get(10, TimeUnit.SECONDS);

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (StreamMessage<String, String> msg : messages) {
                    processMessage(msg, group, handler);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in polling loop", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void recoveryLoop(List<String> streams, String group, String consumerName,
                              Function<EventEnvelope, HandlerResult> handler) {
        Consumer<String> consumer = Consumer.from(group, consumerName);

        while (running.get()) {
            try {
                Thread.sleep(RECOVERY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running.get()) break;

            for (String stream : streams) {
                try {
                    XAutoClaimArgs<String> claimArgs = XAutoClaimArgs.Builder
                        .xautoclaim(consumer, Duration.ofSeconds(60), "0")
                        .count(100);

                    var future = async.xautoclaim(stream, claimArgs);
                    ClaimedMessages<String, String> result = (ClaimedMessages<String, String>)
                        (Object) future.get(10, TimeUnit.SECONDS);

                    if (result == null) continue;

                    List<StreamMessage<String, String>> claimed = result.getMessages();
                    if (claimed == null || claimed.isEmpty()) continue;

                    for (StreamMessage<String, String> msg : claimed) {
                        handleClaimedMessage(stream, group, msg, handler);
                    }
                } catch (Exception e) {
                    log.error("Error in recovery loop for stream {}", stream, e);
                }
            }
        }
    }

    private void handleClaimedMessage(String stream, String group, StreamMessage<String, String> msg,
                                      Function<EventEnvelope, HandlerResult> handler) {
        String msgId = msg.getId();
        Map<String, String> body = msg.getBody();
        String json = body != null ? body.get("envelope") : null;

        if (json == null) {
            async.xack(stream, group, msgId);
            return;
        }

        try {
            EventEnvelope envelope = deserialize(json);
            if (dedupCache.contains(envelope.eventId())) {
                async.xack(stream, group, msgId);
                return;
            }

            HandlerResult hr = handler.apply(envelope);
            if (hr instanceof EventSubscriber.Success) {
                dedupCache.add(envelope.eventId());
                async.xack(stream, group, msgId);
            } else if (hr instanceof EventSubscriber.FatalError) {
                publishToDlq(stream, json);
                async.xack(stream, group, msgId);
            }
        } catch (Exception e) {
            log.error("Error processing claimed message {}", msgId, e);
        }
    }

    private void processMessage(StreamMessage<String, String> msg, String group,
                                Function<EventEnvelope, HandlerResult> handler) {
        String stream = msg.getStream();
        String msgId = msg.getId();
        Map<String, String> body = msg.getBody();
        String json = body != null ? body.get("envelope") : null;

        if (json == null || json.isBlank()) {
            log.warn("Message {} has no envelope field, acking", msgId);
            async.xack(stream, group, msgId);
            return;
        }

        try {
            EventEnvelope envelope = deserialize(json);
            if (dedupCache.contains(envelope.eventId())) {
                log.debug("Dedup: skipping already-processed event {}", envelope.eventId());
                async.xack(stream, group, msgId);
                return;
            }

            HandlerResult result = handler.apply(envelope);
            if (result instanceof EventSubscriber.Success) {
                dedupCache.add(envelope.eventId());
                async.xack(stream, group, msgId);
            } else if (result instanceof EventSubscriber.FatalError fe) {
                log.error("Fatal error processing event {}: {}", envelope.eventId(), fe.reason());
                publishToDlq(stream, json);
                async.xack(stream, group, msgId);
            }
        } catch (Exception e) {
            log.error("Error deserializing or processing message {}", msgId, e);
        }
    }

    private void publishToDlq(String stream, String json) {
        XAddArgs args = new XAddArgs().maxlen(100_000).approximateTrimming();
        async.xadd(stream + ":dlq", args, Map.of("envelope", json));
    }

    private EventEnvelope deserialize(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            String eventId = (String) map.get("eventId");
            String eventType = (String) map.get("eventType");
            int schemaVersion = map.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
            String producer = (String) map.get("producer");
            String causationId = (String) map.get("causationId");
            String correlationId = (String) map.get("correlationId");
            String occurredAtStr = (String) map.get("occurredAt");
            Instant occurredAt = occurredAtStr != null ? Instant.parse(occurredAtStr) : Instant.now();
            Map<String, Object> payload = map.get("payload") instanceof Map<?, ?> payloadMap
                ? payloadMap.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    Map.Entry::getValue
                ))
                : Map.of();

            return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, producer, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize EventEnvelope", e);
        }
    }
}
