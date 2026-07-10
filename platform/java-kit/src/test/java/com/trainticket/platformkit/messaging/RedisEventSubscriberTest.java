package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        Logger logger = (Logger) LoggerFactory.getLogger(RedisEventSubscriber.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> HandlerResult.FATAL_FAILURE);

            for (int i = 0; i < 20 && metadata.get() == null; i++) {
                Thread.sleep(50);
            }
        } finally {
            subscriber.close();
            logger.detachAppender(logs);
        }
        assertThat(metadata.get()).isNotNull();
        assertThat(streams.acked).contains("1-0");
        assertThat(streams.dlqStream).isEqualTo("events:payment");
        assertThat(metadata.get().consumerGroup()).isEqualTo("journey-order");
        assertThat(metadata.get().consumerName()).isEqualTo("consumer-1");
        assertThat(metadata.get().failureReason()).isEqualTo("HandlerResult.FATAL_FAILURE");
        assertThat(metadata.get().attempts()).isEqualTo(1);
        assertThat(metadata.get().deadLetteredAt()).isNotBlank();
        assertThat(logs.list)
            .anySatisfy(eventLog -> {
                assertThat(eventLog.getLoggerName()).isEqualTo(RedisEventSubscriber.class.getName());
                assertThat(eventLog.getLevel()).isEqualTo(Level.WARN);
                assertThat(eventLog.getFormattedMessage()).contains("events:payment", event.eventId(), "HandlerResult.FATAL_FAILURE");
            });
    }


    @Test
    void pollBacksOffExponentiallyOnConsecutiveFailuresAndResetsOnSuccess() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        String eventJson = objectMapper.writeValueAsString(event);
        FailThenSucceedStreams streams = new FailThenSucceedStreams(
            3,
            List.of(new RedisStreamOperations.StreamEntry("1-0", eventJson))
        );

        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);
        Logger logger = (Logger) LoggerFactory.getLogger(RedisEventSubscriber.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        CountDownLatch handled = new CountDownLatch(1);
        try {
            subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> {
                handled.countDown();
                return HandlerResult.SUCCESS;
            });
            assertThat(handled.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscriber.close();
            logger.detachAppender(logs);
        }
        List<String> reconnectLogs = logs.list.stream()
            .filter(e -> e.getFormattedMessage().contains("reconnecting in"))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(reconnectLogs).hasSize(3);
        assertThat(reconnectLogs.get(0)).contains("reconnecting in 1s");
        assertThat(reconnectLogs.get(1)).contains("reconnecting in 2s");
        assertThat(reconnectLogs.get(2)).contains("reconnecting in 4s");
    }


    @Test
    void configuredConsumerThreadsHandleMessagesInParallel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<RedisStreamOperations.StreamEntry> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", String.valueOf(i)));
            messages.add(new RedisStreamOperations.StreamEntry(i + "-0", objectMapper.writeValueAsString(event)));
        }
        FakeRedisStreams streams = new FakeRedisStreams(messages);
        RedisEventSubscriber subscriber = new RedisEventSubscriber(
            streams,
            objectMapper,
            null,
            new InMemoryConsumedEventStore(),
            RedisEventSubscriber.defaultEventConsumerTracer(),
            4
        );
        CountDownLatch allStarted = new CountDownLatch(messages.size());
        CountDownLatch releaseHandlers = new CountDownLatch(1);

        try {
            subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> {
                allStarted.countDown();
                try {
                    releaseHandlers.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return HandlerResult.TRANSIENT_FAILURE;
                }
                return HandlerResult.SUCCESS;
            });

            assertThat(allStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseHandlers.countDown();
            for (int i = 0; i < 20 && streams.acked.size() < messages.size(); i++) {
                Thread.sleep(50);
            }
        } finally {
            releaseHandlers.countDown();
            subscriber.close();
        }
        assertThat(streams.acked).containsExactlyInAnyOrder("0-0", "1-0", "2-0", "3-0");
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

    private static final class FailThenSucceedStreams implements RedisStreamOperations {
        private final int failCount;
        private final List<StreamEntry> successBatch;
        private final List<String> acked = Collections.synchronizedList(new ArrayList<>());
        private int readGroupCalls;
        private boolean delivered;

        private FailThenSucceedStreams(int failCount, List<StreamEntry> successBatch) {
            this.failCount = failCount;
            this.successBatch = successBatch;
        }

        @Override public void createGroup(String stream, String group) {}
        @Override public String publish(String stream, String envelopeJson) { return "1-0"; }

        @Override
        public synchronized List<StreamEntry> readGroup(String stream, String group, String consumerName) {
            readGroupCalls++;
            if (readGroupCalls <= failCount) {
                throw new RuntimeException("Connection refused (os error 111)");
            }
            if (delivered) return List.of();
            delivered = true;
            return successBatch;
        }

        @Override public List<StreamEntry> autoClaim(String stream, String group, String consumerName) { return List.of(); }
        @Override public int deliveryCount(String stream, String group, String messageId) { return 1; }
        @Override public void ack(String stream, String group, String messageId) { acked.add(messageId); }
        @Override public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {}
    }

    private static final class FakeRedisStreams implements RedisStreamOperations {
        private final List<StreamEntry> firstBatch;
        private final List<String> acked = Collections.synchronizedList(new ArrayList<>());
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
