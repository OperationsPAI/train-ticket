package com.trainticket.financesettlement.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.EventPublisher;
import com.trainticket.financesettlement.application.EventSubscriber;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.PublishFailedException;
import com.trainticket.financesettlement.application.SubscribeFailedException;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name = "finance.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close")
    RedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return RedisEventPublisher.fromUrl(properties.url(), objectMapper);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(EventPublisher.class)
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
    RedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return RedisEventSubscriber.fromUrl(properties.url(), objectMapper);
    }

    @Bean
    @Primary
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

    @Bean
    SmartLifecycle financeSubscriptionLifecycle(
        EventSubscriber subscriber,
        RedisMessagingProperties properties,
        FinanceSettlementEventHandler handler
    ) {
        return new SmartLifecycle() {
            private boolean running;

            @Override
            public void start() {
                subscriber.subscribe(
                    RedisMessagingProperties.SUBSCRIBED_STREAMS,
                    RedisMessagingProperties.CONSUMER_GROUP,
                    properties.consumerName(),
                    handler
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
}
