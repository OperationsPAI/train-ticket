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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
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
    void fatalFailureWritesAttributionMetadataToDlq(CapturedOutput output) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope envelope = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        FakeRedisStreams streams = new FakeRedisStreams(List.of(), List.of(new RedisStreamOperations.StreamEntry("1-0", objectMapper.writeValueAsString(envelope))));
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);

        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", ignored -> HandlerResult.FATAL_FAILURE);

        assertThat(streams.dlqFields).hasSize(1);
        Map<String, String> fields = streams.dlqFields.getFirst();
        assertThat(fields.get("envelope")).contains(envelope.eventId());
        assertThat(fields.get("consumerGroup")).isEqualTo("journey-order");
        assertThat(fields.get("consumerName")).isEqualTo("consumer-1");
        assertThat(fields.get("failureReason")).isEqualTo("handler returned FATAL_FAILURE");
        assertThat(fields.get("attempts")).isEqualTo("1");
        assertThat(fields.get("deadLetteredAt")).endsWith("Z");
        assertThat(streams.acked).containsExactly("1-0");
        assertThat(output).contains("service=journey-order stream=events:payment eventId=" + envelope.eventId());
    }

    private static final class FakeRedisStreams implements RedisStreamOperations {
        private final List<StreamEntry> firstBatch;
        private final List<String> acked = new ArrayList<>();
        private final List<StreamEntry> claimedBatch;
        private boolean delivered;
        private boolean claimed;
        private final List<Map<String, String>> dlqFields = new ArrayList<>();

        private FakeRedisStreams(List<StreamEntry> firstBatch) {
            this(firstBatch, List.of());
        }

        private FakeRedisStreams(List<StreamEntry> firstBatch, List<StreamEntry> claimedBatch) {
            this.firstBatch = firstBatch;
            this.claimedBatch = claimedBatch;
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
        public synchronized List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            if (claimed) {
                return List.of();
            }
            claimed = true;
            return claimedBatch;
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
        public void moveToDlq(String stream, String envelopeJson, DeadLetterMetadata metadata) {
            dlqFields.add(metadata.toRedisFields(envelopeJson));
        }
    }
}
