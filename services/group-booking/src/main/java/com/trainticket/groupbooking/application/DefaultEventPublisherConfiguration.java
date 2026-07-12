package com.trainticket.groupbooking.application;

import com.trainticket.platformkit.messaging.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultEventPublisherConfiguration {
    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher inMemoryEventPublisher() {
        return envelope -> {
            // Local/test default; Redis-backed publisher can replace this bean at the boundary.
        };
    }
}
