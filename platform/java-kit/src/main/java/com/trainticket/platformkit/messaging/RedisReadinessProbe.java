package com.trainticket.platformkit.messaging;

/**
 * Exposes Redis connection state for readiness checks without forcing eager
 * connections during Spring bean construction.
 */
public interface RedisReadinessProbe {
    boolean isReady();
}
