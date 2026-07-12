package com.trainticket.journeyorder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.port.out.JourneyOrderEventHandler;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
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

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RedisEventPublisher.class)
    public RedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper) {
        return RedisEventPublisher.fromUrl(redisUrl(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher journeyOrderEventPublisher(RedisEventPublisher publisher) {
        return envelope -> {
            try {
                publisher.publish(envelope);
            } catch (com.trainticket.platformkit.messaging.PublishFailedException exception) {
                throw new EventPublisher.PublishFailed(exception.getMessage(), exception);
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RedisEventSubscriber.class)
    public RedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper) {
        return RedisEventSubscriber.fromUrl(redisUrl(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EventSubscriber.class)
    public EventSubscriber journeyOrderEventSubscriber(RedisEventSubscriber subscriber) {
        return new EventSubscriber() {
            @Override
            public void subscribe(java.util.List<String> streams, String group, String consumerName,
                                  java.util.function.Function<com.trainticket.platformkit.messaging.EventEnvelope, HandlerResult> handler) {
                subscriber.subscribe(streams, group, consumerName, envelope -> switch (handler.apply(envelope)) {
                    case Success ignored -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS;
                    case TransientError ignored -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE;
                    case FatalError ignored -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE;
                });
            }

            @Override
            public void shutdown() {
                subscriber.close();
            }
        };
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
