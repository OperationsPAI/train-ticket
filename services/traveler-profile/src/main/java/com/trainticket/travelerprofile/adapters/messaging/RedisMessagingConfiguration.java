package com.trainticket.travelerprofile.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.EventSubscriber;
import com.trainticket.travelerprofile.application.PublishFailedException;
import com.trainticket.travelerprofile.application.SubscribeFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "traveler-profile.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close") RedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl) { return RedisEventPublisher.fromUrl(redisUrl, objectMapper); }
    @Bean @ConditionalOnMissingBean(EventPublisher.class) EventPublisher redisEventPublisher(RedisEventPublisher publisher) { return envelope -> { try { publisher.publish(envelope); } catch (com.trainticket.platformkit.messaging.PublishFailedException e) { throw new PublishFailedException(e.getMessage(), e); } }; }
    @Bean(destroyMethod = "close") RedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl) { return RedisEventSubscriber.fromUrl(redisUrl, objectMapper); }
    @Bean EventSubscriber redisEventSubscriber(RedisEventSubscriber subscriber) { return (streams, group, consumerName, handler) -> { try { subscriber.subscribe(streams, group, consumerName, envelope -> switch (handler.handle(envelope)) { case SUCCESS -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS; case TRANSIENT_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE; case FATAL_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE; }); } catch (com.trainticket.platformkit.messaging.SubscribeFailedException e) { throw new SubscribeFailedException(e.getMessage(), e); } }; }
}
