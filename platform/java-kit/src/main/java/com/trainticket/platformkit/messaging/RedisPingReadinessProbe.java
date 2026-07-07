package com.trainticket.platformkit.messaging;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;

/**
 * Active Redis readiness probe. It opens a short-lived connection and issues
 * PING on each readiness check so dormant lazy publishers do not make a service
 * look unready just because they have not sent traffic yet.
 */
public final class RedisPingReadinessProbe implements RedisReadinessProbe {
    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(250);

    private final String redisUrl;
    private final Duration timeout;

    public RedisPingReadinessProbe(String redisUrl) {
        this(redisUrl, DEFAULT_TIMEOUT);
    }

    public RedisPingReadinessProbe(String redisUrl, Duration timeout) {
        this.redisUrl = redisUrl == null || redisUrl.isBlank() ? DEFAULT_REDIS_URL : redisUrl;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public boolean isReady() {
        RedisClient client = null;
        try {
            RedisURI uri = RedisURI.create(redisUrl);
            uri.setTimeout(timeout);
            client = RedisClient.create(uri);
            client.setDefaultTimeout(timeout);
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                return "PONG".equalsIgnoreCase(connection.sync().ping());
            }
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }
}
