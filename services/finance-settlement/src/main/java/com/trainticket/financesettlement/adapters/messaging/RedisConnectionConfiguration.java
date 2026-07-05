package com.trainticket.financesettlement.adapters.messaging;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "finance.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConnectionConfiguration {
    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(RedisMessagingProperties properties) {
        return RedisClient.create(properties.url());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }
}
