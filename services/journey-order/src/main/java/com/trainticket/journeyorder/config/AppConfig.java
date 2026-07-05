package com.trainticket.journeyorder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.port.out.JourneyOrderEventHandler;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher redisEventPublisher() {
        return new com.trainticket.journeyorder.adapters.messaging.RedisEventPublisher(redisUrl());
    }

    @Bean
    @ConditionalOnMissingBean(EventSubscriber.class)
    public EventSubscriber redisEventSubscriber() {
        return new com.trainticket.journeyorder.adapters.messaging.RedisStreamSubscriberAdapter(redisUrl());
    }

    @Bean
    public SmartInitializingSingleton journeyOrderSubscriptionStartup(
            EventSubscriber eventSubscriber,
            JourneyOrderEventHandler eventHandler) {
        return () -> eventSubscriber.subscribe(
            com.trainticket.journeyorder.adapters.messaging.RedisJourneyOrderSubscriptions.streams(),
            com.trainticket.journeyorder.adapters.messaging.RedisJourneyOrderSubscriptions.group(),
            "journey-order-" + UUID.randomUUID(),
            eventHandler::handle
        );
    }

    private static String redisUrl() {
        String redisUrl = System.getenv("REDIS_URL");
        return redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl;
    }
}
