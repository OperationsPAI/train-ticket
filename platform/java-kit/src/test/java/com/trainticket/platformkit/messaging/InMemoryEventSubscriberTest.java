package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryEventSubscriberTest {
    @Test
    void transientFailuresUseDeliveryCountsAndDlqOnFifthAttempt() {
        InMemoryEventBus bus = new InMemoryEventBus();
        EventEnvelope envelope = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        bus.publish("events:payment", envelope);
        InMemoryEventSubscriber subscriber = new InMemoryEventSubscriber(bus);

        subscriber.pollOnce(List.of("events:payment"), "journey-order", ignored -> HandlerResult.TRANSIENT_FAILURE);
        for (int i = 0; i < 4; i++) {
            subscriber.recoverOnce("events:payment", "journey-order", ignored -> HandlerResult.TRANSIENT_FAILURE);
        }

        assertThat(bus.dlq("events:payment")).containsExactly(envelope);
    }

    @Test
    void deduplicatesAfterSuccessPerConsumerGroup() {
        InMemoryEventBus bus = new InMemoryEventBus();
        EventEnvelope envelope = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        bus.publish("events:payment", envelope);
        bus.publish("events:payment", envelope);
        InMemoryEventSubscriber subscriber = new InMemoryEventSubscriber(bus);
        int[] calls = {0};

        subscriber.pollOnce(List.of("events:payment"), "journey-order", ignored -> {
            calls[0]++;
            return HandlerResult.SUCCESS;
        });

        assertThat(calls[0]).isEqualTo(1);
        assertThat(bus.dlq("events:payment")).isEmpty();
    }
}
