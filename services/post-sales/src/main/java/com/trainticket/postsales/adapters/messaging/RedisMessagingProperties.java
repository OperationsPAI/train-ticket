package com.trainticket.postsales.adapters.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisMessagingProperties {
    private final String redisUrl;
    private final String consumerName;

    public RedisMessagingProperties(
        @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl,
        @Value("${HOSTNAME:local}") String instanceId
    ) {
        this.redisUrl = redisUrl;
        this.consumerName = RedisStreamNames.CONSUMER_GROUP + "-" + instanceId;
    }

    String redisUrl() {
        return redisUrl;
    }

    String consumerName() {
        return consumerName;
    }
}
