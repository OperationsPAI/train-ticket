package com.trainticket.travelerprofile.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.LazyRedisEventPublisher;
import com.trainticket.platformkit.messaging.LazyRedisEventSubscriber;
import com.trainticket.platformkit.messaging.RedisReadinessProbe;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.EventSubscriber;
import com.trainticket.travelerprofile.application.PublishFailedException;
import com.trainticket.travelerprofile.application.SubscribeFailedException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "traveler-profile.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close")
    LazyRedisEventPublisher platformRedisEventPublisher(
        ObjectMapper objectMapper,
        @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl
    ) {
        return new LazyRedisEventPublisher(redisUrl, objectMapper);
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
    LazyRedisEventSubscriber platformRedisEventSubscriber(
        ObjectMapper objectMapper,
        @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl
    ) {
        return new LazyRedisEventSubscriber(redisUrl, objectMapper);
    }

    @Bean
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
    RedisMessagingReadiness redisMessagingReadiness(
        LazyRedisEventPublisher publisher,
        LazyRedisEventSubscriber subscriber
    ) {
        return new RedisMessagingReadiness(List.of(publisher, subscriber));
    }

    public record RedisMessagingReadiness(List<RedisReadinessProbe> probes) {
        public boolean isReady() {
            return probes.stream().allMatch(RedisReadinessProbe::isReady);
        }
    }
}
