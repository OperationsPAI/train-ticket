package com.trainticket.bookingorchestration.adapters.messaging;

import com.trainticket.bookingorchestration.application.EventEnvelope;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Wires the Redis Streams subscriber on startup when a RedisClient is available.
 * Subscribes to all streams relevant to booking-orchestration per the messaging contract.
 */
@Component
@ConditionalOnBean(RedisStreamsEventSubscriber.class)
public class RedisEventBusInitializer {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBusInitializer.class);

    private final RedisStreamsEventSubscriber subscriber;
    private final String consumerName;

    public RedisEventBusInitializer(RedisStreamsEventSubscriber subscriber,
                                    @Value("${booking-orchestration.subscriber.consumer-name}") String consumerName) {
        this.subscriber = subscriber;
        this.consumerName = consumerName;
    }

    @PostConstruct
    public void startSubscriber() {
        log.info("Starting booking-orchestration event bus subscriber as '{}'", consumerName);

        List<String> streams = List.of(
            "events:capacity-availability",
            "events:journey-order",
            "events:payment",
            "events:provider-integration",
            "events:entitlement-ticketing"
        );

        Function<EventEnvelope, HandlerResult> handler = envelope -> {
            log.debug("Received event: {} (id={})", envelope.eventType(), envelope.eventId());
            // Domain event handling will be wired in future iterations.
            // For now, acknowledge all events.
            return new HandlerResult.Success();
        };

        SubscriberConfig config = new SubscriberConfig(
            streams,
            "booking-orchestration",
            consumerName,
            handler
        );

        subscriber.subscribe(config);
        log.info("Event bus subscriber started for streams: {}", streams);
    }
}
