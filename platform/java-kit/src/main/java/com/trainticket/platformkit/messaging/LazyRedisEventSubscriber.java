package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.observability.EventConsumerTracer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis subscriber that defers Lettuce connection creation until the first poll
 * lifecycle tick. Connection failures back off and only affect readiness.
 */
public final class LazyRedisEventSubscriber implements EventSubscriber, AutoCloseable, RedisReadinessProbe {
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final String redisUrl;
    private final ObjectMapper objectMapper;
    private final EventConsumerTracer eventConsumerTracer;
    private final ScheduledExecutorService connector;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private volatile RedisEventSubscriber delegate;
    private volatile Subscription subscription;
    private volatile boolean ready;
    private volatile long nextConnectionAttemptNanos;
    private volatile Duration reconnectBackoff = INITIAL_BACKOFF;

    public LazyRedisEventSubscriber(String redisUrl, ObjectMapper objectMapper) {
        this(redisUrl, objectMapper, null);
    }

    public LazyRedisEventSubscriber(String redisUrl, ObjectMapper objectMapper, EventConsumerTracer eventConsumerTracer) {
        this.redisUrl = redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.eventConsumerTracer = eventConsumerTracer;
        this.connector = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-subscriber-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void subscribe(List<String> streamNames, String group, String consumerName, EventHandler handler) throws SubscribeFailedException {
        Objects.requireNonNull(streamNames, "streams are required");
        Objects.requireNonNull(handler, "handler is required");
        if (streamNames.isEmpty()) {
            throw new SubscribeFailedException("at least one stream is required", null);
        }
        subscription = new Subscription(List.copyOf(streamNames), group, consumerName, handler);
        if (subscribed.compareAndSet(false, true)) {
            connector.scheduleWithFixedDelay(this::connectOrRetry, 0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private void connectOrRetry() {
        Subscription currentSubscription = subscription;
        if (currentSubscription == null || delegate != null || System.nanoTime() < nextConnectionAttemptNanos) {
            return;
        }
        try {
            RedisEventSubscriber newDelegate = eventConsumerTracer == null
                ? RedisEventSubscriber.fromUrl(redisUrl, objectMapper)
                : RedisEventSubscriber.fromUrl(redisUrl, objectMapper, eventConsumerTracer);
            newDelegate.subscribe(
                currentSubscription.streamNames(),
                currentSubscription.group(),
                currentSubscription.consumerName(),
                currentSubscription.handler()
            );
            delegate = newDelegate;
            ready = true;
            reconnectBackoff = INITIAL_BACKOFF;
        } catch (RuntimeException exception) {
            ready = false;
            closeDelegate();
            backoffNextAttempt();
        }
    }

    private void backoffNextAttempt() {
        nextConnectionAttemptNanos = System.nanoTime() + reconnectBackoff.toNanos();
        reconnectBackoff = reconnectBackoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0
            ? MAX_BACKOFF
            : reconnectBackoff.multipliedBy(2);
    }

    private synchronized void closeDelegate() {
        RedisEventSubscriber current = delegate;
        delegate = null;
        if (current != null) {
            current.close();
        }
    }

    @Override
    public void close() {
        subscribed.set(false);
        connector.shutdownNow();
        closeDelegate();
        ready = false;
    }

    private record Subscription(List<String> streamNames, String group, String consumerName, EventHandler handler) {}
}
