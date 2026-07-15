package com.trainticket.travelerprofile.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.LazyRedisEventPublisher;
import com.trainticket.platformkit.messaging.LazyRedisEventSubscriber;
import com.trainticket.platformkit.messaging.RedisPingReadinessProbe;
import com.trainticket.travelerprofile.application.ConsumedEventLog;
import com.trainticket.travelerprofile.application.DeduplicatingEventHandler;
import com.trainticket.travelerprofile.application.EventPublisher;
import com.trainticket.travelerprofile.application.EventSubscriber;
import com.trainticket.travelerprofile.application.IdentityVerificationEventHandler;
import com.trainticket.travelerprofile.application.PublishFailedException;
import com.trainticket.travelerprofile.application.SubscribeFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

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
    SmartLifecycle travelerProfileSubscriptionLifecycle(
        EventSubscriber subscriber,
        RedisMessagingProperties properties,
        IdentityVerificationEventHandler handler,
        ConsumedEventLog consumedEventLog,
        java.util.Optional<PlatformTransactionManager> transactionManager
    ) {
        return new SmartLifecycle() {
            private boolean running;

            @Override
            public void start() {
                subscriber.subscribe(
                    RedisMessagingProperties.SUBSCRIBED_STREAMS,
                    RedisMessagingProperties.CONSUMER_GROUP,
                    properties.consumerName(),
                    new DeduplicatingEventHandler(consumedEventLog, handler, transactionManager.orElse(null))
                );
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    RedisMessagingReadiness redisMessagingReadiness(
        @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String redisUrl
    ) {
        return new RedisMessagingReadiness(new RedisPingReadinessProbe(redisUrl));
    }

    public record RedisMessagingReadiness(RedisPingReadinessProbe probe) {
        public boolean isReady() {
            return probe.isReady();
        }
    }
}
