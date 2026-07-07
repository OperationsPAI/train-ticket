package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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


    @Test
    void fatalHandlerResultMovesMessageToDlqWithAttributionMetadata() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        FakeRedisStreams streams = new FakeRedisStreams(List.of(
            new RedisStreamOperations.StreamEntry("1-0", objectMapper.writeValueAsString(event))
        ));
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);
        AtomicReference<DlqMetadata> metadata = streams.dlqMetadata;

        subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> HandlerResult.FATAL_FAILURE);

        for (int i = 0; i < 20 && metadata.get() == null; i++) {
            Thread.sleep(50);
        }
        subscriber.close();
        assertThat(metadata.get()).isNotNull();
        assertThat(streams.acked).contains("1-0");
        assertThat(streams.dlqStream).isEqualTo("events:payment");
        assertThat(metadata.get().consumerGroup()).isEqualTo("journey-order");
        assertThat(metadata.get().consumerName()).isEqualTo("consumer-1");
        assertThat(metadata.get().failureReason()).isEqualTo("HandlerResult.FATAL_FAILURE");
        assertThat(metadata.get().attempts()).isEqualTo(1);
        assertThat(metadata.get().deadLetteredAt()).isNotBlank();
    }


    @Test
    void maxDeliveryAttemptsUsesLastRuntimeExceptionAsFailureReasonWithoutRedispatching() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        FakeRedisStreams streams = new FakeRedisStreams(List.of(
            new RedisStreamOperations.StreamEntry("1-0", objectMapper.writeValueAsString(event))
        ));
        streams.autoClaimEnabled = true;
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);

        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", envelope -> {
            throw new IllegalStateException("payment parse failed");
        });
        streams.deliveryCount = RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS;
        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", envelope -> {
            throw new AssertionError("handler must not be called at max delivery attempts");
        });

        assertThat(streams.dlqMetadata.get()).isNotNull();
        assertThat(streams.dlqMetadata.get().failureReason()).isEqualTo("IllegalStateException: payment parse failed");
        assertThat(streams.dlqMetadata.get().attempts()).isEqualTo(RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS);
        assertThat(streams.acked).contains("1-0");
    }

    private static final class FakeRedisStreams implements RedisStreamOperations {
        private final List<StreamEntry> firstBatch;
        private final List<String> acked = new ArrayList<>();
        private boolean delivered;
        private boolean autoClaimEnabled;
        private int autoClaimCalls;
        private int deliveryCount = 1;
        private volatile String dlqStream;
        private final AtomicReference<DlqMetadata> dlqMetadata = new AtomicReference<>();

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
            if (!autoClaimEnabled) {
                return List.of();
            }
            autoClaimCalls++;
            return autoClaimCalls <= 2 ? firstBatch : List.of();
        }

        @Override
        public int deliveryCount(String stream, String group, String messageId) {
            return deliveryCount;
        }

        @Override
        public void ack(String stream, String group, String messageId) {
            acked.add(messageId);
        }

        @Override
        public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {
            this.dlqStream = stream;
            this.dlqMetadata.set(metadata);
        }
    }
}
