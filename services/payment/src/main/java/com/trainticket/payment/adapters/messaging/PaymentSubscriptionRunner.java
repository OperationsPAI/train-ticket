package com.trainticket.payment.adapters.messaging;

import com.trainticket.payment.application.EventSubscriber;
import com.trainticket.payment.application.PaymentInboundEventHandler;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentSubscriptionRunner implements CommandLineRunner {
    private final EventSubscriber subscriber;
    private final PaymentInboundEventHandler handler;

    public PaymentSubscriptionRunner(EventSubscriber subscriber, PaymentInboundEventHandler handler) {
        this.subscriber = subscriber;
        this.handler = handler;
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
            "payment-" + UUID.randomUUID(),
            handler
        );
    }
}
