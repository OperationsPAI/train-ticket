package com.trainticket.bookingorchestration.adapters.messaging;

import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires the Redis Streams subscriber on startup when a RedisClient is available.
 * Subscribes to all streams relevant to booking-orchestration per the messaging contract.
 */
@Component
public class RedisEventBusInitializer {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBusInitializer.class);

    private final com.trainticket.bookingorchestration.application.EventSubscriber subscriber;
    private final BookingOrchestrationService bookingService;
    private final String consumerName;

    public RedisEventBusInitializer(com.trainticket.bookingorchestration.application.EventSubscriber subscriber,
                                    BookingOrchestrationService bookingService,
                                    @Value("${booking-orchestration.subscriber.consumer-name:booking-orchestration-local}") String consumerName) {
        this.subscriber = subscriber;
        this.bookingService = bookingService;
        this.consumerName = consumerName;
    }

    @PostConstruct
    public void startSubscriber() {
        List<String> streams = List.of(
            "events:capacity-availability",
            "events:journey-order",
            "events:payment",
            "events:provider-integration",
            "events:entitlement-ticketing"
        );

        SubscriberConfig config = new SubscriberConfig(
            streams,
            "booking-orchestration",
            consumerName,
            bookingService::handleUpstreamEvent
        );

        subscriber.subscribe(config);
        log.info("Event bus subscriber started for booking-orchestration");
    }
}
