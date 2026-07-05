package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RedisEventSubscriberTest {
    @Test
    void handlerExceptionLeavesMessagePendingAndLoopContinues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope first = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        EventEnvelope second = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "2"));
        FakeRedisStreams streams = new FakeRedisStreams(List.of(
            new RedisStreamOperations.StreamEntry("1-0", objectMapper.writeValueAsString(first)),
            new RedisStreamOperations.StreamEntry("2-0", objectMapper.writeValueAsString(second))
        ));
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);
        CountDownLatch secondHandled = new CountDownLatch(1);

        subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> {
            if (envelope.eventId().equals(first.eventId())) {
                throw new IllegalStateException("boom");
            }
            secondHandled.countDown();
            return HandlerResult.SUCCESS;
        });

        assertThat(secondHandled.await(2, TimeUnit.SECONDS)).isTrue();
        subscriber.close();
        assertThat(streams.acked).containsExactly("2-0");
        assertThat(streams.acked).doesNotContain("1-0");
    }

    private static final class FakeRedisStreams implements RedisStreamOperations {
        private final List<StreamEntry> firstBatch;
        private final List<String> acked = new ArrayList<>();
        private boolean delivered;

        private FakeRedisStreams(List<StreamEntry> firstBatch) {
            this.firstBatch = firstBatch;
        }

        @Override
        public void createGroup(String stream, String group) {
        }

        @Override
        public String publish(String stream, String envelopeJson) {
            return "1-0";
        }

        @Override
        public synchronized List<StreamEntry> readGroup(String stream, String group, String consumerName) {
            if (delivered) {
                return List.of();
            }
            delivered = true;
            return firstBatch;
        }

        @Override
        public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            return List.of();
        }

        @Override
        public int deliveryCount(String stream, String group, String messageId) {
            return 1;
        }

        @Override
        public void ack(String stream, String group, String messageId) {
            acked.add(messageId);
        }

        @Override
        public void moveToDlq(String stream, String envelopeJson) {
        }
    }
}
