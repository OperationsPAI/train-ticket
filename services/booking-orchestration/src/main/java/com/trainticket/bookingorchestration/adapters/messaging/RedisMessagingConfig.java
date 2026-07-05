package com.trainticket.bookingorchestration.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.application.EventPublisher;
import com.trainticket.bookingorchestration.application.EventSubscriber;
import com.trainticket.bookingorchestration.application.PublishFailed;
import com.trainticket.bookingorchestration.application.SubscribeFailed;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisMessagingConfig.class);

    @Bean(destroyMethod = "close")
    public RedisEventPublisher platformRedisEventPublisher(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        log.info("Connecting platform Redis event publisher");
        return RedisEventPublisher.fromUrl(redisUrl, objectMapper);
    }

    @Bean
    public EventPublisher redisEventPublisher(RedisEventPublisher publisher) {
        return envelope -> {
            try {
                publisher.publish(envelope);
            } catch (com.trainticket.platformkit.messaging.PublishFailedException exception) {
                throw new PublishFailed(exception.getMessage(), exception);
            }
        };
    }

    @Bean(destroyMethod = "close")
    public RedisEventSubscriber platformRedisEventSubscriber(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        log.info("Connecting platform Redis event subscriber");
        return RedisEventSubscriber.fromUrl(redisUrl, objectMapper);
    }

    @Bean
    public EventSubscriber redisEventSubscriber(RedisEventSubscriber subscriber) {
        return config -> {
            try {
                subscriber.subscribe(config.streams(), config.group(), config.consumerName(), envelope -> switch (config.handler().apply(envelope)) {
                    case com.trainticket.bookingorchestration.application.HandlerResult.Success ignored -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS;
                    case com.trainticket.bookingorchestration.application.HandlerResult.TransientError ignored -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE;
                    case com.trainticket.bookingorchestration.application.HandlerResult.FatalError ignored -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE;
                });
            } catch (com.trainticket.platformkit.messaging.SubscribeFailedException exception) {
                throw new SubscribeFailed(exception.getMessage(), exception);
            }
        };
    }
}
