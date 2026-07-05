package com.trainticket.adminaudit.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.application.ports.PublishFailedException;
import com.trainticket.adminaudit.application.ports.SubscribeFailedException;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ADMIN_AUDIT_REDIS_ENABLED", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration implements ApplicationRunner {
    private final EventSubscriber subscriber;

    public RedisMessagingConfiguration(EventSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @Bean(destroyMethod = "close")
    RedisEventPublisher platformRedisEventPublisher(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        return RedisEventPublisher.fromUrl(redisUrl, objectMapper);
    }

    @Bean
    EventPublisher redisEventPublisher(RedisEventPublisher publisher) {
        return envelope -> {
            try {
                publisher.publish(envelope);
            } catch (com.trainticket.platformkit.messaging.PublishFailedException exception) {
                throw new PublishFailedException(exception.getMessage(), exception);
            }
        };
    }

    @Bean(destroyMethod = "close")
    RedisEventSubscriber platformRedisEventSubscriber(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        return RedisEventSubscriber.fromUrl(redisUrl, objectMapper);
    }

    @Bean
    EventSubscriber redisEventSubscriber(RedisEventSubscriber subscriber) {
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

    @Override
    public void run(ApplicationArguments args) {
        List<String> streams = List.of();
        if (!streams.isEmpty()) {
            subscriber.subscribe(streams, "admin-audit", "admin-audit-" + UUID.randomUUID(), envelope -> EventSubscriber.HandlerResult.SUCCESS);
        }
    }
}
