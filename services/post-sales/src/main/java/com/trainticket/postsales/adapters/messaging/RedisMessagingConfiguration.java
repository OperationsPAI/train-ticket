package com.trainticket.postsales.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.LazyRedisEventPublisher;
import com.trainticket.platformkit.messaging.LazyRedisEventSubscriber;
import com.trainticket.platformkit.messaging.RedisPingReadinessProbe;
import com.trainticket.postsales.application.EventPublisher;
import com.trainticket.postsales.application.EventSubscriber;
import com.trainticket.postsales.application.PostSalesEventHandler;
import com.trainticket.postsales.application.PublishFailedException;
import com.trainticket.postsales.application.SubscribeFailedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close")
    LazyRedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return new LazyRedisEventPublisher(properties.redisUrl(), objectMapper);
    }

    @Bean
    EventPublisher redisEventPublisher(LazyRedisEventPublisher publisher) {
        return envelope -> {
            try {
                publisher.publish(envelope);
            } catch (com.trainticket.platformkit.messaging.PublishFailedException exception) {
                throw new PublishFailedException(exception.getMessage(), exception);
            }
        };
    }

    @Bean(destroyMethod = "close")
    LazyRedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return new LazyRedisEventSubscriber(properties.redisUrl(), objectMapper);
    }

    @Bean
    @Primary
    EventSubscriber redisEventSubscriber(LazyRedisEventSubscriber subscriber) {
        return (streams, group, consumerName, handler) -> {
            try {
                subscriber.subscribe(streams, group, consumerName, envelope -> switch (handler.handle(envelope)) {
                    case SUCCESS -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS;
                    case TRANSIENT_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE;
                    case FATAL_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE;
                });
            } catch (com.trainticket.platformkit.messaging.SubscribeFailedException exception) {
                throw new SubscribeFailedException(exception.getMessage(), exception);
            }
        };
    }

    @Bean
    RedisSubscriptionLifecycle redisSubscriptionLifecycle(
        EventSubscriber subscriber,
        RedisMessagingProperties properties,
        PostSalesEventHandler handler
    ) {
        return new RedisSubscriptionLifecycle(subscriber, properties, handler);
    }

    @Bean
    RedisMessagingReadiness redisMessagingReadiness(RedisMessagingProperties properties) {
        return new RedisMessagingReadiness(new RedisPingReadinessProbe(properties.redisUrl()));
    }

    public record RedisMessagingReadiness(RedisPingReadinessProbe probe) {
        public boolean isReady() {
            return probe.isReady();
        }
    }
}
