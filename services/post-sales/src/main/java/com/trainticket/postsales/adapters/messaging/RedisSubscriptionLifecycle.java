package com.trainticket.postsales.adapters.messaging;

import com.trainticket.postsales.application.PostSalesEventHandler;
import jakarta.annotation.PostConstruct;
import java.util.List;

public class RedisSubscriptionLifecycle {
    private final com.trainticket.postsales.application.EventSubscriber subscriber;
    private final RedisMessagingProperties properties;
    private final PostSalesEventHandler handler;

    public RedisSubscriptionLifecycle(com.trainticket.postsales.application.EventSubscriber subscriber, RedisMessagingProperties properties, PostSalesEventHandler handler) {
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
