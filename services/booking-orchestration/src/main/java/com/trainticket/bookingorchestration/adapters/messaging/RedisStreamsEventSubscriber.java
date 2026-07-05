package com.trainticket.bookingorchestration.adapters.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.EventEnvelope;
import com.trainticket.bookingorchestration.application.EventSubscriber;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.SubscribeFailed;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisStreamsEventSubscriber implements EventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsEventSubscriber.class);
    private static final int POLL_COUNT = 10;
    private static final int BLOCK_MS = 2000;
    private static final Duration RECOVERY_INTERVAL = Duration.ofSeconds(60);
    private static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final long MIN_IDLE_MS = 60_000L;
    private static final long MAXLEN = 100_000L;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> consumedEventIds = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;
    private volatile SubscriberConfig currentConfig;

    public RedisStreamsEventSubscriber(RedisClient redisClient) {
        this.redisClient = redisClient;
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void subscribe(SubscriberConfig config) {
        if (!running.compareAndSet(false, true)) {
            throw new SubscribeFailed("Subscriber is already running");
        }
        this.currentConfig = config;

        for (String stream : config.streams()) {
            try {
                commands.xgroupCreate(
                    XReadArgs.StreamOffset.from(stream, "$"),
                    config.group(),
                    XGroupCreateArgs.Builder.mkstream(true));
                log.info("Created consumer group '{}' on stream '{}'", config.group(), stream);
            } catch (RedisException e) {
                if (!e.getMessage().contains("BUSYGROUP")) {
                    log.warn("Failed to create consumer group: {}", e.getMessage());
                }
            }
        }

        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "redis-subscriber");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::pollLoop);
        executor.submit(this::recoveryLoop);
    }

    private void pollLoop() {
        String group = currentConfig.group();
        String consumer = currentConfig.consumerName();
        List<String> streams = currentConfig.streams();
        Consumer<String> lettuceConsumer = Consumer.from(group, consumer);

        while (running.get()) {
            try {
                XReadArgs readArgs = XReadArgs.Builder.block(Duration.ofMillis(BLOCK_MS));
                List<StreamMessage<String, String>> messages = commands.xreadgroup(
                    lettuceConsumer,
                    readArgs,
                    streams.stream()
                        .map(s -> XReadArgs.StreamOffset.from(s, ">"))
                        .toArray(XReadArgs.StreamOffset[]::new));

                if (messages == null || messages.isEmpty()) continue;

                for (StreamMessage<String, String> msg : messages) {
                    handleMessage(msg, group);
                }
            } catch (RedisException e) {
                log.error("Error polling Redis streams: {}", e.getMessage());
                sleepSilently(Duration.ofSeconds(1));
            }
        }
    }

    private void recoveryLoop() {
        Consumer<String> lettuceConsumer = Consumer.from(currentConfig.group(), currentConfig.consumerName());

        while (running.get()) {
            try {
                Thread.sleep(RECOVERY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running.get()) break;

            for (String stream : currentConfig.streams()) {
                try {
                    var args = new XAutoClaimArgs<String>()
                        .consumer(lettuceConsumer)
                        .minIdleTime(MIN_IDLE_MS)
                        .startId("0-0")
                        .count(100);
                    var response = commands.xautoclaim(stream, args);
                    List<StreamMessage<String, String>> claimed = response.getMessages();
                    if (claimed != null && !claimed.isEmpty()) {
                        for (StreamMessage<String, String> msg : claimed) {
                            handleClaimedMessage(msg, currentConfig.group());
                        }
                    }
                } catch (RedisException e) {
                    log.warn("XAUTOCLAIM failed for stream {}: {}", stream, e.getMessage());
                }
            }
        }
    }

    private void handleMessage(StreamMessage<String, String> msg, String group) {
        String stream = msg.getStream();
        String messageId = msg.getId();
        Map<String, String> body = msg.getBody();
        String envelopeJson = body != null ? body.get("envelope") : null;

        if (envelopeJson == null || envelopeJson.isBlank()) {
            commands.xack(stream, group, messageId);
            return;
        }

        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            moveToDlq(stream, envelopeJson);
            commands.xack(stream, group, messageId);
            return;
        }

        if (consumedEventIds.contains(envelope.eventId())) {
            commands.xack(stream, group, messageId);
            return;
        }

        processAndAck(stream, group, messageId, envelope, envelopeJson);
    }

    private void handleClaimedMessage(StreamMessage<String, String> msg, String group) {
        String stream = msg.getStream();
        String messageId = msg.getId();
        Map<String, String> body = msg.getBody();
        String envelopeJson = body != null ? body.get("envelope") : null;

        if (envelopeJson == null || envelopeJson.isBlank()) {
            commands.xack(stream, group, messageId);
            return;
        }

        long deliveryCount = getDeliveryCount(stream, group, messageId);
        if (deliveryCount >= MAX_DELIVERY_ATTEMPTS) {
            log.warn("Message {} on {} exceeded max delivery, moving to DLQ", messageId, stream);
            moveToDlq(stream, envelopeJson);
            commands.xack(stream, group, messageId);
            return;
        }

        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            moveToDlq(stream, envelopeJson);
            commands.xack(stream, group, messageId);
            return;
        }

        if (consumedEventIds.contains(envelope.eventId())) {
            commands.xack(stream, group, messageId);
            return;
        }

        processAndAck(stream, group, messageId, envelope, envelopeJson);
    }

    private void processAndAck(String stream, String group, String messageId,
                               EventEnvelope envelope, String envelopeJson) {
        try {
            HandlerResult result = currentConfig.handler().apply(envelope);
            switch (result) {
                case HandlerResult.Success ignored -> {
                    consumedEventIds.add(envelope.eventId());
                    commands.xack(stream, group, messageId);
                }
                case HandlerResult.TransientError ignored -> {
                    // leave in PEL for retry
                }
                case HandlerResult.FatalError ignored -> {
                    moveToDlq(stream, envelopeJson);
                    commands.xack(stream, group, messageId);
                }
            }
        } catch (Exception e) {
            log.error("Handler threw exception: {}", e.getMessage());
        }
    }

    private void moveToDlq(String stream, String envelopeJson) {
        String dlqStream = stream + ":dlq";
        try {
            commands.xadd(dlqStream, XAddArgs.Builder.maxlen(MAXLEN), Map.of("envelope", envelopeJson));
        } catch (RedisException e) {
            log.error("Failed to move message to DLQ: {}", e.getMessage());
        }
    }

    private long getDeliveryCount(String stream, String group, String messageId) {
        try {
            var range = io.lettuce.core.Range.create("-", "+");
            var limit = io.lettuce.core.Limit.create(1, 1);
            var pending = commands.xpending(stream, Consumer.from(group, messageId), range, limit);
            if (pending != null && !pending.isEmpty()) {
                var entry = pending.get(0);
                if (entry != null) return entry.getRedeliveryCount();
            }
        } catch (Exception e) {
            log.debug("Failed to get delivery count: {}", e.getMessage());
        }
        return 0;
    }

    public void shutdown() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (connection != null) connection.close();
        if (redisClient != null) redisClient.shutdown();
    }

    private static void sleepSilently(Duration duration) {
        try { Thread.sleep(duration.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
