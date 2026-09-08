package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The handler executor's queue must be BOUNDED.
 *
 * The incident this guards: the executor was built with
 * Executors.newFixedThreadPool, whose queue is unbounded, so the poll loop could
 * submit without limit. The only thing capping in-flight work was MAX_IN_FLIGHT
 * at 10,000 -- against a handler pool of 1 to 8 threads. Messages sat in that
 * queue holding an in-flight slot while Redis' pending timer ran; XAUTOCLAIM
 * redelivered them; and claimInFlight then DROPPED each redelivery because the
 * slot was still held. journey-order logged 1,948 "in-flight bound reached"
 * lines in a 2,000-line sample, its oldest pending entries showed delivery
 * counts of 10-11, and its backlog grew to 72,281 and kept climbing -- all while
 * the consumer group's entries-read advanced at full rate, which made it look
 * like a healthy consumer that simply could not keep up.
 *
 * The distinguishing property is subtle enough to be worth stating: a bounded
 * queue means the number of messages the subscriber accepts at once is a small
 * multiple of its thread count, so anything it cannot handle stays visible to
 * Redis as pending rather than becoming a shadow backlog inside the process.
 */
class RedisEventSubscriberBackpressureTest {
    /**
     * Feeds a large batch in one read, then blocks every handler so nothing can
     * drain. With a bounded queue the subscriber can only take threads + queue
     * capacity; the rest must be left for redelivery.
     */
    @Test
    void doesNotAcceptMoreMessagesThanTheThreadsAndQueueCanHold() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        int batch = 2_000;
        List<RedisStreamOperations.StreamEntry> entries = new ArrayList<>();
        for (int i = 0; i < batch; i++) {
            EventEnvelope envelope = new EventEnvelopeFactory("payment")
                .create("PaymentCaptured", Map.of("id", String.valueOf(i)));
            entries.add(new RedisStreamOperations.StreamEntry(i + "-0", objectMapper.writeValueAsString(envelope)));
        }

        OneShotStreams streams = new OneShotStreams(entries);
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper, null);

        int consumerThreads = 1; // CONSUMER_THREADS unset in the test environment
        AtomicInteger started = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", envelope -> {
            started.incrementAndGet();
            try {
                // Hold every handler so the queue is the only thing absorbing work.
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return HandlerResult.SUCCESS;
        });

        // Give the poll loop time to submit as much as it is willing to.
        Thread.sleep(700);

        // What matters is how many the subscriber took ownership of, which is how
        // many handlers it started -- not how many readGroup offered.
        int accepted = started.get();
        release.countDown();
        subscriber.close();

        // Every handler blocks, so at most `consumerThreads` can have started. The
        // real subject is the ACK count: with an unbounded queue the subscriber
        // would have taken all 2,000 entries and eventually acked them; with a
        // bounded one it can only ever have accepted threads + queue capacity, so
        // the vast majority stay pending in Redis for redelivery.
        int ceiling = Math.max(consumerThreads * 4, 16) + consumerThreads + 8;
        assertThat(streams.ackCount())
            .as("with an unbounded queue the subscriber would swallow all %d messages, holding an "
                + "in-flight slot for each while Redis redelivered them", batch)
            .isLessThanOrEqualTo(ceiling);
        assertThat(accepted)
            .as("it must still accept work rather than stalling entirely")
            .isPositive();
    }

    /**
     * Streams fake that hands out one batch and records how many entries the
     * subscriber actually took ownership of.
     *
     * "Took ownership" is counted at ack OR at handler entry, because a message
     * left pending is precisely one the subscriber did NOT accept -- that is the
     * behaviour under test.
     */
    private static final class OneShotStreams implements RedisStreamOperations {
        private final List<StreamEntry> batch;
        private final List<String> acked = Collections.synchronizedList(new ArrayList<>());
        private boolean delivered;

        private OneShotStreams(List<StreamEntry> batch) {
            this.batch = batch;
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
            return batch;
        }

        @Override
        public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            return List.of();
        }

        @Override
        public int deliveryCount(String stream, String group, String messageId) {
            return 1;
        }

        int ackCount() {
            return acked.size();
        }

        @Override
        public void ack(String stream, String group, String messageId) {
            acked.add(messageId);
        }

        @Override
        public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {
        }
    }
}
