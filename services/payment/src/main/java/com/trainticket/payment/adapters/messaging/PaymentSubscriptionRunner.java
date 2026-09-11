package com.trainticket.payment.adapters.messaging;

import com.trainticket.payment.application.EventSubscriber;
import com.trainticket.payment.application.PaymentInboundEventHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentSubscriptionRunner implements CommandLineRunner {
    private final EventSubscriber subscriber;
    private final PaymentInboundEventHandler handler;
    private final String consumerName;

    public PaymentSubscriptionRunner(
        EventSubscriber subscriber,
        PaymentInboundEventHandler handler,
        // Stable per-pod consumer name, not a per-boot UUID: a restarted process
        // must reclaim its own pending entries rather than orphan them in a
        // consumer name that will never appear again.
        @Value("${HOSTNAME:local}") String instanceId
    ) {
        this.subscriber = subscriber;
        this.handler = handler;
        this.consumerName = RedisStreamNames.PAYMENT_GROUP + "-" + instanceId;
    }

    @Override
    public void run(String... args) {
        subscriber.subscribe(
            List.of(
                RedisStreamNames.BOOKING_ORCHESTRATION_STREAM,
                RedisStreamNames.POST_SALES_STREAM,
                RedisStreamNames.PAYMENT_CHANNEL_STREAM,
                RedisStreamNames.ANCILLARY_SERVICE_STREAM
            ),
            RedisStreamNames.PAYMENT_GROUP,
            consumerName,
            handler
        );
    }
}
