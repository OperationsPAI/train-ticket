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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisEventSubscriber implements EventSubscriber {
    /**
     * Number of <em>genuine handler failures</em> (a thrown exception or
     * {@link HandlerResult#TRANSIENT_FAILURE}) tolerated for one message before it is dead-lettered.
     * This counts real failures only — never redeliveries caused by a slow or backlogged consumer.
     */
    public static final int MAX_DELIVERY_ATTEMPTS = 5;
    /**
     * Backstop on Redis' own redelivery counter, used only to retire a message that keeps being
     * reclaimed without ever completing — the crash-loop case, where the in-process failure count
     * dies with the process. Redis' counter advances once per reclaim, i.e. once per
     * {@code AUTOCLAIM_MIN_IDLE_MS}, so this threshold is really a duration: at the 5 minute
     * default it is ~8 hours of <em>continuously pending</em> time. That is deliberately far above
     * any credible backlog (the outage that motivated this took ~150 minutes to drain) while still
     * retiring a genuinely stuck message within a working day.
     */
    static final int DEFAULT_MAX_REDELIVERY_ATTEMPTS = 100;
    static final String MAX_REDELIVERY_ATTEMPTS_ENV = "MAX_REDELIVERY_ATTEMPTS";
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisEventSubscriber.class);
    private static final long INITIAL_BACKOFF_SECONDS = 1;
    private static final long MAX_BACKOFF_SECONDS = 30;
    private static final int LAST_FAILURE_CACHE_SIZE = 1_024;
    private static final int DEAD_LETTERED_CACHE_SIZE = 4_096;
    private static final long DEAD_CONSUMER_IDLE_MS = 5 * 60 * 1_000L;
    private static final int DEFAULT_CONSUMER_THREADS = 1;
    private static final String CONSUMER_THREADS_ENV = "CONSUMER_THREADS";
    private static final int MAX_REDELIVERY_ATTEMPTS = maxRedeliveryAttemptsFromEnvironment();
    /**
     * Backstop on the in-flight tracking set.
     *
     * The handler queue is bounded (see the constructor) and will normally reject
     * first, so this should not be reached in steady state. It stays as a guard
     * against the set growing through some path that does not go through
     * submitHandle, and because an unbounded tracking set is a memory leak by
     * construction.
     *
     * It used to be the ONLY bound, at 10,000 against a handler pool of 1-8
     * threads. That gap let the executor's unbounded queue accumulate a shadow
     * backlog: messages held an in-flight slot while queued, Redis' pending timer
     * expired, XAUTOCLAIM redelivered them, and the redelivery was dropped
     * because the slot was still held -- so pending grew without bound while the
     * consumer appeared to be reading at full rate.
     */
    private static final int MAX_IN_FLIGHT = 10_000;

    private final RedisStreamOperations streams;
    private final ObjectMapper objectMapper;
    private final ExecutorService pollExecutor;
    private final ExecutorService handlerExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AutoCloseable closeable;
    private final ConsumedEventStore consumedEvents;
    private final EventConsumerTracer eventConsumerTracer;
    /** Genuine handler failures per message, bounded LRU so a long-lived consumer cannot leak. */
    private final Map<MessageKey, FailureRecord> lastFailures = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MessageKey, FailureRecord> eldest) {
            return size() > LAST_FAILURE_CACHE_SIZE;
        }
    };
    /**
     * Messages currently submitted to (or running on) the handler pool. A message is added exactly
     * once before it is submitted and removed once handling finished, so a concurrent
     * {@code XAUTOCLAIM} that returns the same still-pending message cannot dispatch it a second
     * time and cannot dead-letter it a second time.
     */
    private final Set<MessageKey> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * Message ids already written to the DLQ by this process, so the DLQ write stays exactly-once
     * even if a stale pending entry for an already dead-lettered message is claimed again.
     */
    private final Map<MessageKey, Boolean> deadLettered = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MessageKey, Boolean> eldest) {
            return size() > DEAD_LETTERED_CACHE_SIZE;
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
        // BOUNDED queue, not Executors.newFixedThreadPool's unbounded one.
        //
        // With an unbounded queue the poll loop could submit without limit, so the
        // only thing capping in-flight work was MAX_IN_FLIGHT at 10,000 -- against
        // a handler pool of 1-8 threads. Messages sat in the queue holding an
        // in-flight slot while Redis' pending timer ran, XAUTOCLAIM redelivered
        // them, and the redelivered copies were then dropped by claimInFlight
        // because the slot was still held. journey-order logged 1,948
        // "in-flight bound reached" lines in a 2,000-line sample, with delivery
        // counts of 10-11 on its oldest pending entries and a backlog of 72,281
        // that grew steadily while the consumer looked busy.
        //
        // The queue is sized off the thread count so the two cannot drift: enough
        // depth to keep the handlers fed across a poll, not enough to accumulate a
        // shadow backlog invisible to Redis. submitHandle already handles
        // RejectedExecutionException by releasing the in-flight slot -- that catch
        // was unreachable until now, which is a hint the bounded queue was the
        // original intent.
        this.handlerExecutor = new ThreadPoolExecutor(
            consumerThreads, consumerThreads,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(consumerThreads * 4, 16)),
            namedThreadFactory("redis-subscriber-handler"),
            new ThreadPoolExecutor.AbortPolicy());
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
            if (!claimInFlight(stream, message.id())) {
                continue;
            }
            handle(stream, group, consumerName, message, handler);
        }
    }

    private void poll(List<String> streamNames, String group, String consumerName, EventHandler handler) {
        long backoffSeconds = INITIAL_BACKOFF_SECONDS;
        for (String stream : streamNames) {
            try {
                streams.createGroup(stream, group);
                streams.pruneDeadConsumers(stream, group, consumerName, DEAD_CONSUMER_IDLE_MS);
            } catch (RuntimeException ignored) {
                // best-effort startup cleanup
            }
        }
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
        if (!claimInFlight(stream, message.id())) {
            return;
        }
        try {
            handlerExecutor.submit(() -> handle(stream, group, consumerName, message, handler));
        } catch (RejectedExecutionException exception) {
            releaseInFlight(stream, message.id());
            // A full queue is BACKPRESSURE, not an error: the handlers are busy, so
            // leave the message pending and let Redis redeliver it when they are
            // not. Rethrowing while running would kill the poll loop, which is the
            // opposite of what a saturated consumer needs -- and it is reachable
            // now that the queue is bounded, where before it never was.
            //
            // Logged at DEBUG because under sustained load this is the steady
            // state, not an incident; the in-flight bound warning covers the case
            // where the shortfall is large enough to matter.
            if (running.get()) {
                LOGGER.debug("stream={} messageId={} handler queue full; leaving message pending for redelivery",
                    stream, message.id());
            }
        } catch (RuntimeException exception) {
            releaseInFlight(stream, message.id());
            throw exception;
        }
    }

    /**
     * Reserves {@code (stream, messageId)} for handling. Returns {@code false} when the message is
     * already being handled (so it must not be dispatched again) or when the in-flight bound is
     * reached; in both cases the message simply stays pending in Redis and is redelivered later.
     */
    private boolean claimInFlight(String stream, String messageId) {
        if (inFlight.size() >= MAX_IN_FLIGHT) {
            LOGGER.warn("stream={} messageId={} in-flight bound {} reached; leaving message pending for redelivery", stream, messageId, MAX_IN_FLIGHT);
            return false;
        }
        return inFlight.add(new MessageKey(stream, messageId));
    }

    private void releaseInFlight(String stream, String messageId) {
        inFlight.remove(new MessageKey(stream, messageId));
    }

    private void handle(String stream, String group, String consumerName, RedisStreamOperations.StreamEntry message, EventHandler handler) {
        try {
            handleClaimed(stream, group, consumerName, message, handler);
        } finally {
            releaseInFlight(stream, message.id());
        }
    }

    private void handleClaimed(String stream, String group, String consumerName, RedisStreamOperations.StreamEntry message, EventHandler handler) {
        int redeliveries = streams.deliveryCount(stream, group, message.id());
        String json = message.envelopeJson();
        if (json == null) {
            moveToDlq(stream, group, consumerName, message.id(), "{}", "MissingEnvelope", redeliveries);
            streams.ack(stream, group, message.id());
            return;
        }
        EventEnvelope envelope;
        try {
            envelope = deserialize(json);
        } catch (SubscribeFailedException exception) {
            moveToDlq(stream, group, consumerName, message.id(), json, exception, redeliveries);
            streams.ack(stream, group, message.id());
            return;
        }
        // Duplicate detection must precede every give-up path: an event this group already
        // processed is a redelivery to swallow, never a poison message to dead-letter, no matter
        // how many times Redis has handed it back.
        if (consumedEvents.alreadyConsumed(group, envelope.eventId())) {
            streams.ack(stream, group, message.id());
            forgetFailures(stream, message.id());
            return;
        }
        // Crash backstop only. Redis' redelivery counter advances once per reclaim, so it measures
        // time spent pending rather than handler failures; it is deliberately not the primary
        // give-up signal. See MAX_REDELIVERY_ATTEMPTS.
        if (redeliveries >= MAX_REDELIVERY_ATTEMPTS) {
            RuntimeException lastException = forgetFailures(stream, message.id());
            if (lastException != null) {
                moveToDlq(stream, group, consumerName, message.id(), json, lastException, redeliveries);
            } else {
                moveToDlq(stream, group, consumerName, message.id(), json, "MaxRedeliveriesWithoutCompletion", redeliveries);
            }
            streams.ack(stream, group, message.id());
            return;
        }
        HandlerResult result;
        try (EventConsumerTracer.SpanScope span = eventConsumerTracer.start(stream, group, envelope)) {
            try {
                result = handler.handle(envelope);
            } catch (RuntimeException exception) {
                span.recordException(exception);
                failed(stream, group, consumerName, message.id(), json, envelope, exception, null);
                return;
            }
            if (result == HandlerResult.SUCCESS) {
                consumedEvents.recordConsumed(group, envelope.eventId());
                streams.ack(stream, group, message.id());
                forgetFailures(stream, message.id());
            } else if (result == HandlerResult.FATAL_FAILURE) {
                span.markError("HandlerResult.FATAL_FAILURE");
                forgetFailures(stream, message.id());
                moveToDlq(stream, group, consumerName, message.id(), json, "HandlerResult.FATAL_FAILURE", redeliveries);
                streams.ack(stream, group, message.id());
            } else if (result == HandlerResult.TRANSIENT_FAILURE) {
                span.markError("HandlerResult.TRANSIENT_FAILURE");
                failed(stream, group, consumerName, message.id(), json, envelope, null, "HandlerResult.TRANSIENT_FAILURE");
            }
        }
    }

    /**
     * Records one genuine handler failure and dead-letters once {@link #MAX_DELIVERY_ATTEMPTS}
     * real failures have been observed for this message. Below the threshold the message is left
     * pending so Redis redelivers it.
     */
    private void failed(
        String stream,
        String group,
        String consumerName,
        String messageId,
        String json,
        EventEnvelope envelope,
        RuntimeException exception,
        String reason
    ) {
        int failures = recordFailure(stream, messageId, exception);
        if (failures < MAX_DELIVERY_ATTEMPTS) {
            LOGGER.warn(
                "service={} stream={} eventId={} failures={}/{} handler failed; message stays pending for retry",
                group, stream, envelope.eventId(), failures, MAX_DELIVERY_ATTEMPTS, exception);
            return;
        }
        forgetFailures(stream, messageId);
        if (exception != null) {
            moveToDlq(stream, group, consumerName, messageId, json, exception, failures);
        } else {
            moveToDlq(stream, group, consumerName, messageId, json, Objects.toString(reason, "MaxHandlerFailures"), failures);
        }
        streams.ack(stream, group, messageId);
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

    private static int maxRedeliveryAttemptsFromEnvironment() {
        return maxRedeliveryAttempts(System.getenv(MAX_REDELIVERY_ATTEMPTS_ENV));
    }

    static int configuredMaxRedeliveryAttempts() {
        return MAX_REDELIVERY_ATTEMPTS;
    }

    static int maxRedeliveryAttempts(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_REDELIVERY_ATTEMPTS;
        }
        try {
            int attempts = Integer.parseInt(configured.trim());
            // Must stay above the genuine-failure threshold, or the time-based backstop would
            // preempt it and reintroduce dead-lettering of merely-slow messages.
            if (attempts > MAX_DELIVERY_ATTEMPTS) {
                return attempts;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning below
        }
        LOGGER.warn(
            "{}={} must be an integer greater than {}; using default {}",
            MAX_REDELIVERY_ATTEMPTS_ENV, configured, MAX_DELIVERY_ATTEMPTS, DEFAULT_MAX_REDELIVERY_ATTEMPTS);
        return DEFAULT_MAX_REDELIVERY_ATTEMPTS;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private int recordFailure(String stream, String messageId, RuntimeException exception) {
        synchronized (lastFailures) {
            MessageKey key = new MessageKey(stream, messageId);
            FailureRecord previous = lastFailures.get(key);
            int failures = previous == null ? 1 : previous.failures() + 1;
            RuntimeException lastException = exception != null || previous == null ? exception : previous.lastException();
            lastFailures.put(key, new FailureRecord(failures, lastException));
            return failures;
        }
    }

    /** Clears the failure history for a message and returns the last exception seen, if any. */
    private RuntimeException forgetFailures(String stream, String messageId) {
        synchronized (lastFailures) {
            FailureRecord removed = lastFailures.remove(new MessageKey(stream, messageId));
            return removed == null ? null : removed.lastException();
        }
    }

    /** Identity of one Redis Stream entry: unique per stream and stable across redeliveries. */
    private record MessageKey(String stream, String messageId) {}

    /** Genuine handler failures observed for one message, and the most recent exception. */
    private record FailureRecord(int failures, RuntimeException lastException) {}

    private void moveToDlq(String stream, String group, String consumerName, String messageId, String envelopeJson, Throwable reason, int attempts) {
        String reasonText = reason.getClass().getSimpleName() + ": " + Objects.toString(reason.getMessage(), "");
        moveToDlq(stream, group, consumerName, messageId, envelopeJson, reasonText, attempts);
    }

    private void moveToDlq(String stream, String group, String consumerName, String messageId, String envelopeJson, String reason, int attempts) {
        if (!markDeadLettered(stream, messageId)) {
            LOGGER.debug("service={} stream={} messageId={} already dead-lettered; skipping duplicate DLQ write", group, stream, messageId);
            return;
        }
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
        try {
            streams.moveToDlq(stream, envelopeJson, new DlqMetadata(group, consumerName, truncatedReason, Math.max(1, attempts), Instant.now().toString()));
        } catch (RuntimeException exception) {
            // The write did not land, so allow a later delivery to retry the DLQ hand-off.
            forgetDeadLettered(stream, messageId);
            throw exception;
        }
    }

    /**
     * Returns {@code true} the first time a message id is dead-lettered, {@code false} afterwards.
     * The DLQ write and the ack that retires the message are not atomic, so a stale pending entry
     * for the same message must never produce a second DLQ record.
     */
    private boolean markDeadLettered(String stream, String messageId) {
        synchronized (deadLettered) {
            return deadLettered.put(new MessageKey(stream, messageId), Boolean.TRUE) == null;
        }
    }

    private void forgetDeadLettered(String stream, String messageId) {
        synchronized (deadLettered) {
            deadLettered.remove(new MessageKey(stream, messageId));
        }
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
        // Tasks still queued when the pool shuts down never reach their finally block.
        inFlight.clear();
        synchronized (lastFailures) {
            lastFailures.clear();
        }
        synchronized (deadLettered) {
            deadLettered.clear();
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
