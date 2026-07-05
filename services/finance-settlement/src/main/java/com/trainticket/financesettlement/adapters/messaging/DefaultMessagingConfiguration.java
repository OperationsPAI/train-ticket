package com.trainticket.financesettlement.adapters.messaging;

import com.trainticket.financesettlement.application.EventPublisher;
import com.trainticket.financesettlement.application.EventSubscriber;
import com.trainticket.platformkit.messaging.InMemoryEventBus;
import com.trainticket.platformkit.messaging.InMemoryEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultMessagingConfiguration {
    @Bean
    @ConditionalOnMissingBean
    InMemoryEventBus inMemoryEventBus() { return new InMemoryEventBus(); }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    EventPublisher defaultEventPublisher(InMemoryEventBus bus) { return new InMemoryEventPublisher(bus)::publish; }

    @Bean
    @ConditionalOnMissingBean(EventSubscriber.class)
    EventSubscriber defaultEventSubscriber() { return (streams, group, consumerName, handler) -> { }; }
}
