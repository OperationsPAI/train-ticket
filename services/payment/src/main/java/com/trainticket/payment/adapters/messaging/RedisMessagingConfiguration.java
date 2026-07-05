package com.trainticket.payment.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "shutdown")
    RedisClient paymentRedisClient(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        return RedisClient.create(redisUrl);
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> paymentRedisConnection(RedisClient paymentRedisClient) {
        return paymentRedisClient.connect();
    }

    @Bean
    RedisEventPublisher redisEventPublisher(StatefulRedisConnection<String, String> connection, ObjectMapper objectMapper) {
        return new RedisEventPublisher(connection, objectMapper);
    }

    @Bean
    RedisStreamOperations redisStreamOperations(StatefulRedisConnection<String, String> connection) {
        return new LettuceRedisStreamOperations(connection);
    }

    @Bean
    RedisStreamSubscriberAdapter redisEventSubscriber(RedisStreamOperations streams, ObjectMapper objectMapper) {
        return new RedisStreamSubscriberAdapter(streams, objectMapper);
    }
}
