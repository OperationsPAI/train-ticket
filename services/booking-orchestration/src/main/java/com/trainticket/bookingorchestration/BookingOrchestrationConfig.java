package com.trainticket.bookingorchestration;

import com.trainticket.bookingorchestration.adapters.messaging.InMemoryEventPublisher;
import com.trainticket.bookingorchestration.application.EventPublisher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingOrchestrationConfig {
    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public EventPublisher eventPublisher() { return new InMemoryEventPublisher(); }
}
