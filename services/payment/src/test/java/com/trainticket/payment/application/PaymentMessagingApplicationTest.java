package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.trainticket.payment.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentMessagingApplicationTest {
    @Test
    void publisherWrapsDomainEventsInContractEnvelope() {
        FakeEventPublisher publisher = new FakeEventPublisher();
        PaymentCommandService service = new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), publisher);

        service.createIntent("ord-123", "purchase", Money.fromMinorUnits(35000, "CNY"), "acct-1", "0194f2e0-7b3e-7610-0284-5c26e8b0c555", "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444");

        EventEnvelope envelope = publisher.published().getFirst();
        assertEquals("PaymentIntentCreated", envelope.eventType());
        assertEquals("payment", envelope.producer());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444", envelope.correlationId());
        assertEquals("cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555", envelope.causationId());
        assertEquals("2026-07-05T10:30:00Z", envelope.occurredAt().toString());
        Map<?, ?> payload = assertInstanceOf(Map.class, envelope.payload());
        assertEquals(Map.of("currency", "CNY", "minorUnits", 35000L), payload.get("amount"));
    }

    @Test
    void subscriberDeduplicatesDuplicateEventIds() {
        ConsumedEventDeduplicator deduplicator = new ConsumedEventDeduplicator();
        PaymentInboundEventHandler handler = new PaymentInboundEventHandler(deduplicator);
        FakeEventSubscriber subscriber = new FakeEventSubscriber();
        subscriber.subscribe(List.of("ignored"), "payment", "payment-test", handler);
        EventEnvelope envelope = new EventEnvelope("evt-1", "PostSalesApproved", Instant.parse("2026-07-05T10:30:00Z"), "corr-1", "cause-1", "post-sales", 1, Map.of("x", "y"));

        assertEquals(HandlerResult.SUCCESS, subscriber.emit(envelope));
        assertEquals(HandlerResult.SUCCESS, subscriber.emit(envelope));
    }
}
