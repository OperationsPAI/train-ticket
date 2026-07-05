package com.trainticket.bookingorchestration;

import com.trainticket.bookingorchestration.adapters.messaging.InMemoryEventPublisher;
import com.trainticket.bookingorchestration.application.EventPublisher;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingOrchestrationConfig {

    private static final Logger log = LoggerFactory.getLogger(BookingOrchestrationConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher inMemoryEventPublisher() {
        log.info("Using InMemoryEventPublisher (no Redis publisher bean defined)");
        return new InMemoryEventPublisher();
    }
}
