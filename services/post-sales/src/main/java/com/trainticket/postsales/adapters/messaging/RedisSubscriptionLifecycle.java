package com.trainticket.postsales.adapters.messaging;

import com.trainticket.postsales.application.PostSalesEventHandler;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true")
public class RedisSubscriptionLifecycle {
    private final RedisEventSubscriber subscriber;
    private final RedisMessagingProperties properties;
    private final PostSalesEventHandler handler;

    public RedisSubscriptionLifecycle(RedisEventSubscriber subscriber, RedisMessagingProperties properties, PostSalesEventHandler handler) {
        this.subscriber = subscriber;
        this.properties = properties;
        this.handler = handler;
    }

    @PostConstruct
    void subscribe() {
        subscriber.subscribe(
            List.of(
                RedisStreamNames.forProducer("capacity-availability"),
                RedisStreamNames.forProducer("journey-order"),
                RedisStreamNames.forProducer("disruption-recovery")
            ),
            RedisStreamNames.CONSUMER_GROUP,
            properties.consumerName(),
            handler::handle
        );
    }
}
