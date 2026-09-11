package com.trainticket.groupbooking.infrastructure;

import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
            CapacityHoldEventHandler handler,
            // Stable per-pod consumer name, not a per-boot UUID: a restarted
            // process must reclaim its own pending entries rather than orphan
            // them in a consumer name that will never appear again.
            @Value("${HOSTNAME:local}") String instanceId) {
        return () -> subscriber.subscribe(
            STREAMS,
            GROUP,
            GROUP + "-" + instanceId,
            handler::handle
        );
    }
}
