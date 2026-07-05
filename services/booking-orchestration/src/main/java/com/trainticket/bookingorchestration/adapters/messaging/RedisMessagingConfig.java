package com.trainticket.bookingorchestration.adapters.messaging;

import com.trainticket.bookingorchestration.application.EventPublisher;
import io.lettuce.core.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis Streams messaging configuration — all Redis types live here,
 * never outside the adapters/messaging/ package.
 */
@Configuration
@ConditionalOnProperty("REDIS_URL")
public class RedisMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisMessagingConfig.class);

    @Bean
    public RedisClient redisClient(@Value("${REDIS_URL}") String redisUrl) {
        log.info("Connecting to Redis at {}", redisUrl);
        return RedisClient.create(redisUrl);
    }

    @Bean(destroyMethod = "shutdown")
    public EventPublisher redisEventPublisher(RedisClient redisClient) {
        log.info("Using RedisStreamsEventPublisher");
        RedisStreamsEventPublisher publisher = new RedisStreamsEventPublisher(redisClient);
        return publisher;
    }

    @Bean(destroyMethod = "shutdown")
    public RedisStreamsEventSubscriber redisEventSubscriber(RedisClient redisClient) {
        log.info("Using RedisStreamsEventSubscriber");
        return new RedisStreamsEventSubscriber(redisClient);
    }
}
