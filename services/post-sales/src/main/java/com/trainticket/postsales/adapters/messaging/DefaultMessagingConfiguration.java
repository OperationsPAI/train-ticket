package com.trainticket.postsales.adapters.messaging;

import com.trainticket.postsales.application.EventPublisher;
import com.trainticket.platformkit.messaging.InMemoryEventBus;
import com.trainticket.platformkit.messaging.InMemoryEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultMessagingConfiguration {
    @Bean
    @ConditionalOnMissingBean
    InMemoryEventBus inMemoryEventBus() {
        return new InMemoryEventBus();
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher defaultEventPublisher(InMemoryEventBus bus) {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher(bus);
        return publisher::publish;
    }
}
