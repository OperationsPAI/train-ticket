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
import java.util.concurrent.atomic.AtomicInteger;
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
    void deadLettersWithTheLastRuntimeExceptionOnlyAfterMaxObservedHandlerFailures() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(List.of(
            new RedisStreamOperations.StreamEntry("1-0", objectMapper.writeValueAsString(event))
        ));
        // A healthy consumer: Redis reports a single delivery, so only real failures can count.
        streams.deliveryCount = 1;
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);
        EventHandler throwing = envelope -> {
            throw new IllegalStateException("payment parse failed");
        };

        for (int failure = 1; failure < RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS; failure++) {
            subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", throwing);
            assertThat(streams.dlqWrites).as("failure %d must not dead-letter yet", failure).isEmpty();
            assertThat(streams.acked).isEmpty();
        }
        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", throwing);
        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", envelope -> {
            throw new AssertionError("handler must not be called once the message was dead-lettered");
        });
        subscriber.close();

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("IllegalStateException: payment parse failed");
        assertThat(streams.dlqWrites.getFirst().metadata().attempts()).isEqualTo(RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS);
        assertThat(streams.acked).containsExactly("1-0");
    }

    @Test
    void transientFailureResultsCountTowardTheSameFailureBudget() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(List.of(
            new RedisStreamOperations.StreamEntry("2-0", objectMapper.writeValueAsString(event))
        ));
        streams.deliveryCount = 1;
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);

        for (int failure = 0; failure < RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS; failure++) {
            subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", envelope -> HandlerResult.TRANSIENT_FAILURE);
        }
        subscriber.close();

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("HandlerResult.TRANSIENT_FAILURE");
        assertThat(streams.acked).containsExactly("2-0");
    }

    @Test
    void slowConsumerWhoseRedeliveryCountGrewWithoutFailuresIsNeverDeadLettered() throws Exception {
        // Reproduces the production false positives. Redis' redelivery counter advances once per
        // reclaim, so a backlog that takes ~150 minutes to drain inflates it purely by waiting,
        // with zero handler failures. Such a message must still be handled, never dead-lettered.
        long backlogMinutes = 150;
        int redeliveriesFromWaitingAlone =
            (int) (backlogMinutes * 60_000L / LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        assertThat(redeliveriesFromWaitingAlone).isGreaterThan(RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS);

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("transfer-management").create("TransferPlanCreated", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(List.of(
            new RedisStreamOperations.StreamEntry("3-0", objectMapper.writeValueAsString(event))
        ));
        streams.deliveryCount = redeliveriesFromWaitingAlone;
        ConsumedEventStore consumedEvents = new InMemoryConsumedEventStore();
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null, consumedEvents);
        List<String> handled = Collections.synchronizedList(new ArrayList<>());

        subscriber.recoverOnce("events:transfer-management", "journey-order", "consumer-1", envelope -> {
            handled.add(envelope.eventId());
            return HandlerResult.SUCCESS;
        });
        subscriber.close();

        assertThat(handled).containsExactly(event.eventId());
        assertThat(streams.dlqWrites).isEmpty();
        assertThat(streams.acked).containsExactly("3-0");
        assertThat(consumedEvents.alreadyConsumed("journey-order", event.eventId())).isTrue();
    }

    @Test
    void redeliveryBackstopWindowIsMuchLongerThanAnyCredibleBacklog() {
        // Breaks the "N minutes of backlog == N genuine failures" equivalence: the give-up signal
        // for real failures is time-independent (5 failures), and the time-based backstop only
        // trips after hours of continuously pending time, not minutes.
        long backstopMinutes = RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS
            * LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS / 60_000L;
        assertThat(backstopMinutes).isGreaterThan(3 * 150);
        long failureBudgetMinutesUnderOldCoupling =
            RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS * LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS / 60_000L;
        assertThat(backstopMinutes).isGreaterThan(failureBudgetMinutesUnderOldCoupling * 10);
    }

    @Test
    void alreadyConsumedEventIsAckedAsDuplicateEvenWhenTheRedeliveryCountExceedsEveryThreshold() throws Exception {
        // The dedup check must win over every give-up path: this event is in processed_events, so
        // it was handled successfully and is only being redelivered because the ack was lost.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("transfer-management").create("MctRuleCreated", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(List.of(
            new RedisStreamOperations.StreamEntry("4-0", objectMapper.writeValueAsString(event))
        ));
        streams.deliveryCount = Math.max(144, RedisEventSubscriber.configuredMaxRedeliveryAttempts() * 10);
        ConsumedEventStore consumedEvents = new InMemoryConsumedEventStore();
        consumedEvents.recordConsumed("journey-order", event.eventId());
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null, consumedEvents);

        subscriber.recoverOnce("events:transfer-management", "journey-order", "consumer-1", envelope -> {
            throw new AssertionError("an already consumed event must not reach the handler");
        });
        subscriber.close();

        assertThat(streams.dlqWrites).isEmpty();
        assertThat(streams.acked).containsExactly("4-0");
    }

    @Test
    void redeliveryBackstopStillRetiresAMessageThatNeverCompletes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(List.of(
            new RedisStreamOperations.StreamEntry("5-0", objectMapper.writeValueAsString(event))
        ));
        // Simulates the crash-loop case: the consumer dies before it can record a failure, so the
        // in-process failure count is always lost and only Redis' counter keeps climbing.
        streams.deliveryCount = RedisEventSubscriber.configuredMaxRedeliveryAttempts();
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);

        subscriber.recoverOnce("events:payment", "journey-order", "consumer-1", envelope -> {
            throw new AssertionError("handler must not be called once the backstop trips");
        });
        subscriber.close();

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("MaxRedeliveriesWithoutCompletion");
        assertThat(streams.dlqWrites.getFirst().metadata().attempts()).isEqualTo(RedisEventSubscriber.configuredMaxRedeliveryAttempts());
        assertThat(streams.acked).containsExactly("5-0");
    }

    @Test
    void redeliveryBackstopIsFarAboveTheGenuineFailureBudgetAndRejectsUnsafeOverrides() {
        assertThat(RedisEventSubscriber.maxRedeliveryAttempts(null))
            .isEqualTo(RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS);
        assertThat(RedisEventSubscriber.maxRedeliveryAttempts("   "))
            .isEqualTo(RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS);
        assertThat(RedisEventSubscriber.maxRedeliveryAttempts("not-a-number"))
            .isEqualTo(RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS);
        // An override at or below the genuine-failure budget would re-couple dead-lettering to
        // elapsed pending time, so it is refused.
        assertThat(RedisEventSubscriber.maxRedeliveryAttempts(String.valueOf(RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS)))
            .isEqualTo(RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS);
        assertThat(RedisEventSubscriber.maxRedeliveryAttempts("120")).isEqualTo(120);
        assertThat(RedisEventSubscriber.DEFAULT_MAX_REDELIVERY_ATTEMPTS)
            .isGreaterThan(RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS * 5);
    }

    @Test
    void redeliveryBackstopDeadLettersExactlyOnceWhenAutoClaimRepeatsBeforeAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("transfer-management").create("TransferRequested", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(
            List.of(new RedisStreamOperations.StreamEntry("7-0", objectMapper.writeValueAsString(event)))
        );
        streams.deliveryCount = RedisEventSubscriber.configuredMaxRedeliveryAttempts();

        deadLetterWhileAutoClaimRepeats(streams, objectMapper, envelope -> {
            throw new AssertionError("handler must not be called once the backstop trips");
        });

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("MaxRedeliveriesWithoutCompletion");
        assertThat(streams.dlqEventIds()).containsExactly(event.eventId());
        assertThat(streams.acked).containsExactly("7-0");
    }

    @Test
    void missingEnvelopeDeadLettersExactlyOnceWhenAutoClaimRepeatsBeforeAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedeliveringStreams streams = new RedeliveringStreams(
            List.of(new RedisStreamOperations.StreamEntry("8-0", null))
        );
        streams.deliveryCount = 1;

        deadLetterWhileAutoClaimRepeats(streams, objectMapper, envelope -> HandlerResult.SUCCESS);

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("MissingEnvelope");
        assertThat(streams.acked).containsExactly("8-0");
    }

    @Test
    void undeserializableEnvelopeDeadLettersExactlyOnceWhenAutoClaimRepeatsBeforeAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedeliveringStreams streams = new RedeliveringStreams(
            List.of(new RedisStreamOperations.StreamEntry("9-0", "{not json"))
        );
        streams.deliveryCount = 1;

        deadLetterWhileAutoClaimRepeats(streams, objectMapper, envelope -> HandlerResult.SUCCESS);

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).contains("SubscribeFailedException");
        assertThat(streams.acked).containsExactly("9-0");
    }

    @Test
    void fatalHandlerResultDeadLettersExactlyOnceWhenAutoClaimRepeatsBeforeAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("transfer-management").create("TransferRequested", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(
            List.of(new RedisStreamOperations.StreamEntry("10-0", objectMapper.writeValueAsString(event)))
        );
        streams.deliveryCount = 1;

        deadLetterWhileAutoClaimRepeats(streams, objectMapper, envelope -> HandlerResult.FATAL_FAILURE);

        assertThat(streams.dlqWrites).hasSize(1);
        assertThat(streams.dlqWrites.getFirst().metadata().failureReason()).isEqualTo("HandlerResult.FATAL_FAILURE");
        assertThat(streams.acked).containsExactly("10-0");
    }

    @Test
    void inFlightTrackingIsReleasedSoTheSameMessageIdCanBeRedeliveredLater() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = new EventEnvelopeFactory("transfer-management").create("TransferRequested", Map.of("id", "1"));
        RedeliveringStreams streams = new RedeliveringStreams(
            List.of(new RedisStreamOperations.StreamEntry("11-0", objectMapper.writeValueAsString(event)))
        );
        streams.deliveryCount = 1;
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);
        List<String> handledEventIds = Collections.synchronizedList(new ArrayList<>());
        EventHandler transientFailure = envelope -> {
            handledEventIds.add(envelope.eventId());
            return HandlerResult.TRANSIENT_FAILURE;
        };

        // A transient failure releases the reservation, so the next claim must dispatch again.
        subscriber.recoverOnce("events:transfer-management", "journey-order", "consumer-1", transientFailure);
        subscriber.recoverOnce("events:transfer-management", "journey-order", "consumer-1", transientFailure);
        subscriber.close();

        assertThat(handledEventIds).hasSize(2);
        assertThat(streams.dlqWrites).isEmpty();
        assertThat(streams.acked).isEmpty();
    }

    /**
     * Runs the real poll loop against a stream that keeps returning the same still-pending entry
     * from autoClaim, with the DLQ write blocked so the window between the DLQ hand-off and the ack
     * that retires the message stays open while further claims arrive.
     */
    private static void deadLetterWhileAutoClaimRepeats(
        RedeliveringStreams streams,
        ObjectMapper objectMapper,
        EventHandler handler
    ) throws Exception {
        streams.blockDlqWrites = new CountDownLatch(1);
        RedisEventSubscriber subscriber = new RedisEventSubscriber(
            streams,
            objectMapper,
            null,
            new InMemoryConsumedEventStore(),
            RedisEventSubscriber.defaultEventConsumerTracer(),
            4
        );
        try {
            subscriber.subscribe(List.of("events:transfer-management"), "journey-order", "consumer-1", handler);
            assertThat(streams.dlqEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 40 && streams.autoClaimCalls.get() < 10; i++) {
                Thread.sleep(25);
            }
            assertThat(streams.autoClaimCalls.get()).isGreaterThan(1);
            streams.blockDlqWrites.countDown();
            for (int i = 0; i < 40 && streams.acked.isEmpty(); i++) {
                Thread.sleep(25);
            }
            Thread.sleep(200);
        } finally {
            streams.blockDlqWrites.countDown();
            subscriber.close();
        }
    }

    /**
     * Returns the same still-pending entry on every autoClaim, the way Redis does until the message
     * is acked. Every DLQ hand-off is recorded so duplicates are visible.
     */
    private static final class RedeliveringStreams implements RedisStreamOperations {
        private final List<StreamEntry> pending;
        private final List<String> acked = Collections.synchronizedList(new ArrayList<>());
        private final List<DlqWrite> dlqWrites = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger autoClaimCalls = new AtomicInteger();
        private final CountDownLatch dlqEntered = new CountDownLatch(1);
        private volatile int deliveryCount = RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS;
        private volatile CountDownLatch blockDlqWrites;

        private RedeliveringStreams(List<StreamEntry> pending) {
            this.pending = pending;
        }

        @Override
        public void createGroup(String stream, String group) {
        }

        @Override
        public String publish(String stream, String envelopeJson) {
            return "1-0";
        }

        @Override
        public List<StreamEntry> readGroup(String stream, String group, String consumerName) {
            return List.of();
        }

        @Override
        public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            autoClaimCalls.incrementAndGet();
            return acked.isEmpty() ? pending : List.of();
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
            dlqEntered.countDown();
            CountDownLatch gate = blockDlqWrites;
            if (gate != null) {
                try {
                    gate.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            dlqWrites.add(new DlqWrite(stream, envelopeJson, metadata));
        }

        private List<String> dlqEventIds() {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            return dlqWrites.stream()
                .map(write -> {
                    try {
                        return mapper.readValue(write.envelopeJson(), EventEnvelope.class).eventId();
                    } catch (Exception exception) {
                        return "unparseable";
                    }
                })
                .toList();
        }
    }


    private record DlqWrite(String stream, String envelopeJson, DlqMetadata metadata) {}

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
