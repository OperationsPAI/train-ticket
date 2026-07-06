package com.trainticket.postsales.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import com.trainticket.postsales.application.EventPublisher;
import com.trainticket.postsales.application.EventSubscriber;
import com.trainticket.postsales.application.PostSalesEventHandler;
import com.trainticket.postsales.application.PublishFailedException;
import com.trainticket.postsales.application.SubscribeFailedException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close") RedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) { return RedisEventPublisher.fromUrl(properties.redisUrl(), objectMapper); }
    @Bean @Primary EventPublisher redisEventPublisher(RedisEventPublisher publisher) { return envelope -> { try { publisher.publish(envelope); } catch (com.trainticket.platformkit.messaging.PublishFailedException e) { throw new PublishFailedException(e.getMessage(), e); } }; }
    @Bean(destroyMethod = "close") RedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) { return RedisEventSubscriber.fromUrl(properties.redisUrl(), objectMapper); }
    @Bean @Primary EventSubscriber redisEventSubscriber(RedisEventSubscriber subscriber) { return (streams, group, consumerName, handler) -> { try { subscriber.subscribe(streams, group, consumerName, envelope -> switch (handler.handle(envelope)) { case SUCCESS -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS; case TRANSIENT_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE; case FATAL_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE; }); } catch (com.trainticket.platformkit.messaging.SubscribeFailedException e) { throw new SubscribeFailedException(e.getMessage(), e); } }; }
    @Bean RedisSubscriptionLifecycle redisSubscriptionLifecycle(EventSubscriber subscriber, RedisMessagingProperties properties, PostSalesEventHandler handler) { return new RedisSubscriptionLifecycle(subscriber, properties, handler); }
}
