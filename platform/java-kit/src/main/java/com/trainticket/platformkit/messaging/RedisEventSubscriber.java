package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.observability.EventConsumerTracer;
import com.trainticket.platformkit.observability.GlobalEventConsumerTracer;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisEventSubscriber implements EventSubscriber {
    public static final int MAX_DELIVERY_ATTEMPTS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisEventSubscriber.class);
    private static final long INITIAL_BACKOFF_SECONDS = 1;
    private static final long MAX_BACKOFF_SECONDS = 30;
    private static final int LAST_FAILURE_CACHE_SIZE = 1_024;
    private static final int DEFAULT_CONSUMER_THREADS = 1;
    private static final String CONSUMER_THREADS_ENV = "CONSUMER_THREADS";

    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final ExecutorService pollExecutor;
    private final ExecutorService handlerExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AutoCloseable closeable;
    private final ConsumedEventStore consumedEvents;
    private final EventConsumerTracer eventConsumerTracer;
    private final Map<FailureKey, RuntimeException> lastFailures = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<FailureKey, RuntimeException> eldest) {
            return size() > LAST_FAILURE_CACHE_SIZE;
        }
    };

    public RedisEventSubscriber(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        this(new LettuceRedisStreamOperations(connection), objectMapper, connection::close);
    }

    public static RedisEventSubscriber fromUrl(String redisUrl, ObjectMapper objectMapper) {
        return fromUrl(redisUrl, objectMapper, defaultEventConsumerTracer());
    }

    public static RedisEventSubscriber fromUrl(String redisUrl, ObjectMapper objectMapper, EventConsumerTracer eventConsumerTracer) {
        LazyLettuceRedisStreamOperations streams = new LazyLettuceRedisStreamOperations(redisUrl);
        return new RedisEventSubscriber(streams, objectMapper, streams, new InMemoryConsumedEventStore(), eventConsumerTracer);
    }

    RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable) {
        this(streams, objectMapper, closeable, new InMemoryConsumedEventStore());
    }

    RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable, ConsumedEventStore consumedEvents) {
        this(streams, objectMapper, closeable, consumedEvents, defaultEventConsumerTracer());
    }

    RedisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper, AutoCloseable closeable, ConsumedEventStore consumedEvents, EventConsumerTracer eventConsumerTracer) {
        this(streams, objectMapper, closeable, consumedEvents, eventConsumerTracer, consumerThreadsFromEnvironment());
    }

    RedisEventSubscriber(
        RedisStreamOperations streams,
        ObjectMapper objectMapper,
        AutoCloseable closeable,
        ConsumedEventStore consumedEvents,
        EventConsumerTracer eventConsumerTracer,
        int consumerThreads
    ) {
        if (consumerThreads < 1) {
            throw new IllegalArgumentException("consumerThreads must be positive");
        }
        this.streams = Objects.requireNonNull(streams, "streams are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.closeable = closeable;
        this.consumedEvents = Objects.requireNonNull(consumedEvents, "consumedEvents is required");
        this.eventConsumerTracer = Objects.requireNonNull(eventConsumerTracer, "eventConsumerTracer is required");
        this.pollExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("redis-subscriber-poll"));
        this.handlerExecutor = Executors.newFixedThreadPool(consumerThreads, namedThreadFactory("redis-subscriber-handler"));
    }

    @Override
    public void subscribe(List<String> streamNames, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        Objects.requireNonNull(streamNames, "streams are required");
        Objects.requireNonNull(handler, "handler is required");
        if (streamNames.isEmpty()) {
            throw new SubscribeFailedException("at least one stream is required", null);
        }
        running.set(true);
        pollExecutor.submit(() -> poll(streamNames, group, consumerName, handler));
    }

    public void recoverOnce(String stream, String group, String consumerName, EventHandler handler) {
        for (RedisStreamOperations.StreamEntry message : streams.autoClaim(stream, group, consumerName)) {
            handle(stream, group, consumerName, message, handler);
        }
    }

    private void poll(List<String> streamNames, String group, String consumerName, EventHandler handler) {
        long backoffSeconds = INITIAL_BACKOFF_SECONDS;
        while (running.get()) {
            for (String stream : streamNames) {
                try {
                    streams.createGroup(stream, group);
                    recover(stream, group, consumerName, handler);
                    for (RedisStreamOperations.StreamEntry message : streams.readGroup(stream, group, consumerName)) {
                        submitHandle(stream, group, consumerName, message, handler);
                    }
                    backoffSeconds = INITIAL_BACKOFF_SECONDS;
                } catch (RuntimeException exception) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    LOGGER.warn("service={} stream={} poll iteration failed; reconnecting in {}s", group, stream, backoffSeconds, exception);
                    sleepQuietly(backoffSeconds * 1_000L);
                    backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
                }
            }
        }
    }

    private void recover(String stream, String group, String consumerName, EventHandler handler) {
        for (RedisStreamOperations.StreamEntry message : streams.autoClaim(stream, group, consumerName)) {
            submitHandle(stream, group, consumerName, message, handler);
        }
    }

    private void submitHandle(String stream, String group, String consumerName, RedisStreamOperations.StreamEntry message, EventHandler handler) {
        try {
            handlerExecutor.submit(() -> handle(stream, group, consumerName, message, handler));
        } catch (RejectedExecutionException exception) {
            if (running.get()) {
                throw exception;
            }
        }
    }

    private void handle(String stream, String group, String consumerName, RedisStreamOperations.StreamEntry message, EventHandler handler) {
        int deliveryAttempts = streams.deliveryCount(stream, group, message.id());
        String json = message.envelopeJson();
        if (json == null) {
            moveToDlq(stream, group, consumerName, message.id(), "{}", "MissingEnvelope", deliveryAttempts);
            streams.ack(stream, group, message.id());
            return;
        }
        EventEnvelope envelope;
        try {
            envelope = deserialize(json);
        } catch (SubscribeFailedException exception) {
            moveToDlq(stream, group, consumerName, message.id(), json, exception, deliveryAttempts);
            streams.ack(stream, group, message.id());
            return;
        }
        if (deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {
            RuntimeException lastException = removeLastFailure(stream, message.id());
            if (lastException != null) {
                moveToDlq(stream, group, consumerName, message.id(), json, lastException, deliveryAttempts);
            } else {
                moveToDlq(stream, group, consumerName, message.id(), json, "MaxDeliveryAttempts", deliveryAttempts);
            }
            streams.ack(stream, group, message.id());
            return;
        }
        if (consumedEvents.alreadyConsumed(group, envelope.eventId())) {
            streams.ack(stream, group, message.id());
            return;
        }
        HandlerResult result;
        try (EventConsumerTracer.SpanScope span = eventConsumerTracer.start(stream, group, envelope)) {
            try {
                result = handler.handle(envelope);
            } catch (RuntimeException exception) {
                span.recordException(exception);
                LOGGER.warn(
                    "service={} stream={} eventId={} attempt={} handler threw; message stays pending for retry",
                    group, stream, envelope.eventId(), deliveryAttempts, exception);
                rememberLastFailure(stream, message.id(), exception);
                return;
            }
            if (result == HandlerResult.SUCCESS) {
                consumedEvents.recordConsumed(group, envelope.eventId());
                streams.ack(stream, group, message.id());
                removeLastFailure(stream, message.id());
            } else if (result == HandlerResult.FATAL_FAILURE) {
                span.markError("HandlerResult.FATAL_FAILURE");
                removeLastFailure(stream, message.id());
                moveToDlq(stream, group, consumerName, message.id(), json, "HandlerResult.FATAL_FAILURE", deliveryAttempts);
                streams.ack(stream, group, message.id());
            } else if (result == HandlerResult.TRANSIENT_FAILURE) {
                span.markError("HandlerResult.TRANSIENT_FAILURE");
            }
        }
    }

    static EventConsumerTracer defaultEventConsumerTracer() {
        return new GlobalEventConsumerTracer(RedisEventSubscriber.class.getName());
    }

    private static int consumerThreadsFromEnvironment() {
        String configured = System.getenv(CONSUMER_THREADS_ENV);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CONSUMER_THREADS;
        }
        try {
            int threads = Integer.parseInt(configured.trim());
            if (threads > 0) {
                return threads;
            }
        } catch (NumberFormatException exception) {
            LOGGER.warn("{}={} is not a valid positive integer; using default {}", CONSUMER_THREADS_ENV, configured, DEFAULT_CONSUMER_THREADS);
            return DEFAULT_CONSUMER_THREADS;
        }
        LOGGER.warn("{}={} must be positive; using default {}", CONSUMER_THREADS_ENV, configured, DEFAULT_CONSUMER_THREADS);
        return DEFAULT_CONSUMER_THREADS;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void rememberLastFailure(String stream, String messageId, RuntimeException exception) {
        synchronized (lastFailures) {
            lastFailures.put(new FailureKey(stream, messageId), exception);
        }
    }

    private RuntimeException removeLastFailure(String stream, String messageId) {
        synchronized (lastFailures) {
            return lastFailures.remove(new FailureKey(stream, messageId));
        }
    }

    private record FailureKey(String stream, String messageId) {}

    private void moveToDlq(String stream, String group, String consumerName, String messageId, String envelopeJson, Throwable reason, int attempts) {
        String reasonText = reason.getClass().getSimpleName() + ": " + Objects.toString(reason.getMessage(), "");
        moveToDlq(stream, group, consumerName, messageId, envelopeJson, reasonText, attempts);
    }

    private void moveToDlq(String stream, String group, String consumerName, String messageId, String envelopeJson, String reason, int attempts) {
        EventEnvelope envelope = tryDeserialize(envelopeJson);
        String eventId = envelope == null ? "unknown" : envelope.eventId();
        String truncatedReason = truncate(reason);
        LOGGER.warn(
            "service={} stream={} eventId={} failureReason={} moving message to DLQ",
            group,
            stream,
            eventId,
            truncatedReason
        );
        streams.moveToDlq(stream, envelopeJson, new DlqMetadata(group, consumerName, truncatedReason, Math.max(1, attempts), Instant.now().toString()));
    }

    private EventEnvelope tryDeserialize(String json) {
        try {
            return deserialize(json);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String truncate(String reason) {
        String value = Objects.toString(reason, "unknown");
        return value.length() <= 500 ? value : value.substring(0, 500);
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
        pollExecutor.shutdownNow();
        handlerExecutor.shutdown();
        try {
            pollExecutor.awaitTermination(5, TimeUnit.SECONDS);
            handlerExecutor.awaitTermination(5, TimeUnit.SECONDS);
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
