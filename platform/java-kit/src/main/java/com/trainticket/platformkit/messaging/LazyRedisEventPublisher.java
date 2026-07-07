package com.trainticket.platformkit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;

/**
 * Redis publisher that opens its Lettuce connection on first publish, not at
 * Spring bean construction time. Failed connection attempts are backed off and
 * exposed through readiness rather than aborting service startup.
 */
public final class LazyRedisEventPublisher implements EventPublisher, AutoCloseable, RedisReadinessProbe {
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final String redisUrl;
    private final ObjectMapper objectMapper;

    private volatile RedisEventPublisher delegate;
    private volatile boolean ready;
    private volatile long nextConnectionAttemptNanos;
    private volatile Duration reconnectBackoff = INITIAL_BACKOFF;

    public LazyRedisEventPublisher(String redisUrl, ObjectMapper objectMapper) {
        this.redisUrl = redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public void publish(EventEnvelope envelope) throws PublishFailedException {
        try {
            ensureDelegate().publish(envelope);
            ready = true;
            reconnectBackoff = INITIAL_BACKOFF;
        } catch (RuntimeException exception) {
            ready = false;
            closeDelegate();
            backoffNextAttempt();
            throw exception;
        }
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private RedisEventPublisher ensureDelegate() {
        RedisEventPublisher current = delegate;
        if (current != null) {
            return current;
        }
        if (System.nanoTime() < nextConnectionAttemptNanos) {
            throw new PublishFailedException("redis publisher is backing off after a connection failure", null);
        }
        synchronized (this) {
            current = delegate;
            if (current == null) {
                current = RedisEventPublisher.fromUrl(redisUrl, objectMapper);
                delegate = current;
            }
            return current;
        }
    }

    private void backoffNextAttempt() {
        nextConnectionAttemptNanos = System.nanoTime() + reconnectBackoff.toNanos();
        reconnectBackoff = reconnectBackoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0
            ? MAX_BACKOFF
            : reconnectBackoff.multipliedBy(2);
    }

    private synchronized void closeDelegate() {
        RedisEventPublisher current = delegate;
        delegate = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception exception) {
                throw new PublishFailedException("redis publisher could not close", exception);
            }
        }
    }

    @Override
    public void close() {
        closeDelegate();
        ready = false;
    }
}
