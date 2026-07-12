package com.trainticket.marketingcampaign.application;

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
        };
    }
}
