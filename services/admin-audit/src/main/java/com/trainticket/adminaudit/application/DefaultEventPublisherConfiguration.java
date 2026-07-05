package com.trainticket.adminaudit.application;

import com.trainticket.adminaudit.application.ports.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultEventPublisherConfiguration {
    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher inMemoryEventPublisher() {
        return new EventPublisher() {
            @Override
            public void publish(EventEnvelope envelope) {
                // In-memory default for local/test runtime without Redis.
            }
        };
    }
}
