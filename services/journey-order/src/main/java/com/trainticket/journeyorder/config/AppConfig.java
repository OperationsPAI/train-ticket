package com.trainticket.journeyorder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.port.out.EventPublisher;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.adapters.messaging.RedisJourneyOrderSubscriptions;
import com.trainticket.journeyorder.application.port.out.JourneyOrderEventHandler;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import java.time.Clock;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
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
            JourneyOrderEventHandler eventHandler,
            // Stable per-pod consumer name, not a per-boot UUID: a restarted
            // process must reclaim its own pending entries rather than orphan
            // them in a consumer name that will never appear again.
            @Value("${HOSTNAME:local}") String instanceId) {
        return () -> eventSubscriber.subscribe(
            com.trainticket.journeyorder.adapters.messaging.RedisJourneyOrderSubscriptions.streams(),
            com.trainticket.journeyorder.adapters.messaging.RedisJourneyOrderSubscriptions.group(),
            "journey-order-" + instanceId,
            // Filter by event type BEFORE entering the handler.
            //
            // OrderManagementService.handle is @Transactional, so Spring opens a
            // database transaction on entry and the method's own early returns
            // cannot avoid it. It then reads processed_events and writes it back on
            // the way out. That fixed cost applied to every event on all 11
            // subscribed streams, including the eleven types the switch explicitly
            // ignores and everything caught by its `default`.
            //
            // Measured, not assumed: with handler timing instrumented, event types
            // whose entire body is `new Success()` -- OfferQuoted,
            // TravelerProfileUpdated and the rest -- took a p50 of 49 ms each. That
            // is the transaction and the dedup round-trip, nothing else. journey-order
            // was running a p50 of 714 ms per event overall against a backlog that
            // grew continuously, so removing this floor from the majority of its
            // traffic is the single largest lever available.
            //
            // Skipping is safe for exactly the reason it was safe in post-sales: an
            // event that reaches no handler has no side effect, so there is nothing
            // for processed_events to deduplicate on a redelivery.
            envelope -> RedisJourneyOrderSubscriptions.isActionable(envelope.eventType())
                ? eventHandler.handle(envelope)
                : new EventSubscriber.Success()
        );
    }

    private static String redisUrl() {
        String redisUrl = System.getenv("REDIS_URL");
        return redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl;
    }
}
