package com.trainticket.travelerprofile.adapters.messaging;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

final class RedisEventSubscriberConnectionFactory implements AutoCloseable {
    private final RedisClient client;

    RedisEventSubscriberConnectionFactory(String redisUrl) {
        this.client = RedisClient.create(redisUrl);
    }

    StatefulRedisConnection<String, String> connect() {
        return client.connect();
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
