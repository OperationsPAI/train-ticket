package com.trainticket.groupbooking.infrastructure;

import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.SmartInitializingSingleton;

@Configuration
@ConditionalOnBean(RedisEventSubscriber.class)
public class EventSubscriberConfiguration {

    private static final List<String> STREAMS = List.of("events:capacity-availability");
    private static final String GROUP = "group-booking";

    @Bean
    public SmartInitializingSingleton groupBookingSubscriptionStartup(
            RedisEventSubscriber subscriber,
            CapacityHoldEventHandler handler) {
        return () -> subscriber.subscribe(
            STREAMS,
            GROUP,
            GROUP + "-" + UUID.randomUUID(),
            handler::handle
        );
    }
}
