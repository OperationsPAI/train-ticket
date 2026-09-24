package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentIntentStatus;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.payment.domain.ports.RefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryLatePaymentCaseRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The channel handoff runs in three phases: a short transaction that commits the
 * channel keys, the HTTP call itself outside every transaction, and a second short
 * transaction that applies the channel result. These tests pin that shape and the
 * single submission guarantee for concurrent attempts.
 */
class PaymentChannelHandoffTransactionTest {
    private static final Instant FIXED_NOW = Instant.parse("2026-07-05T10:30:00Z");
    private static final Money AMOUNT = Money.fromMinorUnits(1_000, "CNY");

    @Test
    void captureCommitsChannelKeysBeforeTheCallAndAppliesTheResultInASecondTransaction() {
        Fixture fixture = new Fixture();
        PaymentIntent intent = fixture.createIntent();
        int commitsBeforeCapture = fixture.transactions.commits();

        PaymentIntent captured = fixture.service.captureIntent(intent.paymentIntentId(), "idem-capture", "corr-capture");

        assertEquals(PaymentIntentStatus.CAPTURED, captured.status());
        assertEquals(1, fixture.channel.captureCalls.size());
        ChannelCall call = fixture.channel.captureCalls.getFirst();
        assertFalse(call.transactionActive(), "the channel call must run outside every database transaction");
        assertTrue(call.keysPersistedInTransaction(), "the order and submit keys must be committed before the channel call");
        assertEquals(2, fixture.transactions.commits() - commitsBeforeCapture, "one transaction for the keys and one for the result");
        assertTrue(fixture.intents.saveRecords().stream().allMatch(SaveRecord::transactionActive), "every intent write must run inside a transaction");
        assertEquals(List.of("PaymentIntentCreated", "PaymentCaptured"), fixture.publisher.eventTypes());
    }

    @Test
    void concurrentCaptureAttemptsSubmitOneKeyPairAndCaptureOnce() throws Exception {
        Fixture fixture = new Fixture();
        PaymentIntent intent = fixture.createIntent();
        fixture.channel.captureBarrier = new CountDownLatch(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<PaymentIntent> first = workers.submit(() -> {
                start.await();
                return fixture.service.captureIntent(intent.paymentIntentId(), "idem-first", "corr-first");
            });
            Future<PaymentIntent> second = workers.submit(() -> {
                start.await();
                return fixture.service.captureIntent(intent.paymentIntentId(), "idem-second", "corr-second");
            });
            start.countDown();

            assertEquals(PaymentIntentStatus.CAPTURED, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(PaymentIntentStatus.CAPTURED, second.get(10, TimeUnit.SECONDS).status());
        } finally {
            workers.shutdownNow();
        }

        assertEquals(2, fixture.channel.captureCalls.size());
        assertEquals(1, fixture.channel.distinctCaptureKeyPairs(), "both attempts must submit the same channel order and submit keys");
        assertEquals(0, fixture.channel.captureCalls.stream().filter(ChannelCall::transactionActive).count());
        assertEquals(1, fixture.publisher.count("PaymentCaptured"));
        assertEquals(PaymentIntentStatus.CAPTURED, fixture.intents.findById(intent.paymentIntentId()).orElseThrow().status());
    }

    @Test
    void concurrentRefundAttemptsSubmitOneKeyPairAndRecordOneSubmission() throws Exception {
        Fixture fixture = new Fixture();
        PaymentIntent intent = fixture.createIntent();
        PaymentIntent captured = fixture.service.captureIntent(intent.paymentIntentId(), "idem-capture", "corr-capture");
        ChannelRef originalRoute = captured.channelRef();
        assertNotNull(originalRoute.channelOrderId());
        assertNotNull(originalRoute.channelTransactionId());
        fixture.channel.refundBarrier = new CountDownLatch(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Refund> first = workers.submit(() -> {
                start.await();
                return fixture.service.requestRefund(intent.paymentIntentId(), AMOUNT, "post-sales-approved", "case-1", "idem-refund", "corr-refund", originalRoute);
            });
            Future<Refund> second = workers.submit(() -> {
                start.await();
                return fixture.service.requestRefund(intent.paymentIntentId(), AMOUNT, "post-sales-approved", "case-1", "idem-refund", "corr-refund", originalRoute);
            });
            start.countDown();

            assertEquals(RefundStatus.SUBMITTED, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(RefundStatus.SUBMITTED, second.get(10, TimeUnit.SECONDS).status());
        } finally {
            workers.shutdownNow();
        }

        assertEquals(2, fixture.channel.refundCalls.size());
        assertEquals(1, fixture.channel.distinctRefundKeyPairs(), "both attempts must submit the same refund and submit keys");
        assertEquals(0, fixture.channel.refundCalls.stream().filter(RefundCall::transactionActive).count());
        List<Refund> refunds = fixture.refunds.findByBusinessCaseRef("case-1");
        assertEquals(1, refunds.size(), "the replay must reuse the recorded refund");
        assertEquals(1, refunds.getFirst().attemptCount(), "the channel submission must be recorded once");
        assertEquals(1, fixture.publisher.count("RefundRequested"));
    }

    private record SaveRecord(String orderKey, String submitKey, boolean transactionActive) {
    }

    private record RefundSaveRecord(String refundKey, String submitKey, boolean transactionActive) {
    }

    record ChannelCall(String orderKey, String submitKey, boolean transactionActive, boolean keysPersistedInTransaction) {
    }

    record RefundCall(String refundKey, String submitKey, boolean transactionActive, boolean keysPersistedInTransaction) {
    }

    static final class ObservingPaymentIntentRepository implements PaymentIntentRepository {
        private final PaymentIntentRepository delegate = new InMemoryPaymentIntentRepository();
        private final List<SaveRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<PaymentIntent> findById(String paymentIntentId) {
            return delegate.findById(paymentIntentId);
        }

        @Override
        public Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public Optional<PaymentIntent> findLatestByBusinessRef(String businessRef) {
            return delegate.findLatestByBusinessRef(businessRef);
        }

        @Override
        public List<PaymentIntent> findExpiredOpenIntents(Instant now, int limit) {
            return delegate.findExpiredOpenIntents(now, limit);
        }

        @Override
        public void save(PaymentIntent intent) {
            records.add(new SaveRecord(intent.channelOrderIdempotencyKey(), intent.channelOrderSubmitIdempotencyKey(), transactionActive()));
            delegate.save(intent);
        }

        List<SaveRecord> saveRecords() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }

        boolean keysPersistedInTransaction(String orderKey, String submitKey) {
            synchronized (records) {
                return records.stream().anyMatch(record -> record.transactionActive()
                    && orderKey.equals(record.orderKey()) && submitKey.equals(record.submitKey()));
            }
        }
    }

    static final class ObservingRefundRepository implements RefundRepository {
        private final RefundRepository delegate = new InMemoryRefundRepository();
        private final List<RefundSaveRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<Refund> findById(String refundId) {
            return delegate.findById(refundId);
        }

        @Override
        public List<Refund> findByBusinessCaseRef(String businessCaseRef) {
            return delegate.findByBusinessCaseRef(businessCaseRef);
        }

        @Override
        public void save(Refund refund) {
            records.add(new RefundSaveRecord(refund.channelRefundIdempotencyKey(), refund.channelRefundSubmitIdempotencyKey(), transactionActive()));
            delegate.save(refund);
        }

        List<RefundSaveRecord> saveRecords() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }

        boolean keysPersistedInTransaction(String refundKey, String submitKey) {
            synchronized (records) {
                return records.stream().anyMatch(record -> record.transactionActive()
                    && refundKey.equals(record.refundKey()) && submitKey.equals(record.submitKey()));
            }
        }
    }

    static final class RecordingChannelClient implements PaymentChannelClient {
        private final ObservingPaymentIntentRepository intents;
        private final ObservingRefundRepository refunds;
        private final List<ChannelCall> captureCalls = new CopyOnWriteArrayList<>();
        private final List<RefundCall> refundCalls = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch captureBarrier;
        private volatile CountDownLatch refundBarrier;

        RecordingChannelClient(ObservingPaymentIntentRepository intents, ObservingRefundRepository refunds) {
            this.intents = intents;
            this.refunds = refunds;
        }

        @Override
        public HandoffOrder handoffCapture(PaymentIntent intent, String orderIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef requestedRef) {
            captureCalls.add(new ChannelCall(orderIdempotencyKey, submitIdempotencyKey, transactionActive(),
                intents.keysPersistedInTransaction(orderIdempotencyKey, submitIdempotencyKey)));
            awaitConcurrentArrival(captureBarrier);
            String suffix = submitIdempotencyKey.substring(submitIdempotencyKey.length() - 8);
            ChannelRef ref = new ChannelRef(requestedRef.channel(), "co-" + suffix, null, "txn-" + suffix, null, null, null);
            return new HandoffOrder(ref.channelOrderId(), "SUCCEEDED", ref.channelTransactionId(), ref);
        }

        @Override
        public HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String refundIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef originalRoute) {
            refundCalls.add(new RefundCall(refundIdempotencyKey, submitIdempotencyKey, transactionActive(),
                refunds.keysPersistedInTransaction(refundIdempotencyKey, submitIdempotencyKey)));
            awaitConcurrentArrival(refundBarrier);
            String suffix = submitIdempotencyKey.substring(submitIdempotencyKey.length() - 8);
            ChannelRef ref = new ChannelRef(originalRoute.channel(), originalRoute.channelOrderId(), "cr-" + suffix,
                originalRoute.channelTransactionId(), "rftxn-" + suffix, null, null);
            return new HandoffRefund(ref.channelRefundId(), "SUCCEEDED", ref.channelRefundTransactionId(), ref);
        }

        long distinctCaptureKeyPairs() {
            return captureCalls.stream().map(call -> call.orderKey() + "|" + call.submitKey()).distinct().count();
        }

        long distinctRefundKeyPairs() {
            return refundCalls.stream().map(call -> call.refundKey() + "|" + call.submitKey()).distinct().count();
        }

        private static void awaitConcurrentArrival(CountDownLatch barrier) {
            if (barrier == null) return;
            barrier.countDown();
            try {
                if (!barrier.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the concurrent channel submission did not arrive");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    static final class RecordingEventPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }

        List<String> eventTypes() {
            return published.stream().map(EventEnvelope::eventType).toList();
        }

        long count(String eventType) {
            return published.stream().filter(envelope -> envelope.eventType().equals(eventType)).count();
        }
    }

    /**
     * The production repository takes a row lock for the duration of each short
     * transaction, so concurrent attempts serialize on it. This manager gives the
     * same single writer guarantee to the in memory repositories.
     */
    static final class SerializingTransactionManager extends AbstractPlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger commits = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lock.lock();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
            lock.unlock();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            lock.unlock();
        }

        int commits() {
            return commits.get();
        }
    }

    static final class Fixture {
        final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        final RecordingEventPublisher publisher = new RecordingEventPublisher();
        final ObservingPaymentIntentRepository intents = new ObservingPaymentIntentRepository();
        final ObservingRefundRepository refunds = new ObservingRefundRepository();
        final SerializingTransactionManager transactions = new SerializingTransactionManager();
        final TransactionTemplate template = new TransactionTemplate(transactions);
        final RecordingChannelClient channel;
        final PaymentCommandService service;

        Fixture() {
            channel = new RecordingChannelClient(intents, refunds);
            service = new PaymentCommandService(
                clock,
                publisher,
                intents,
                refunds,
                new InMemoryReservationPaymentRequestRepository(),
                new InMemoryLatePaymentCaseRepository(),
                channel,
                ChannelRouter.defaults(),
                Optional.of(transactions)
            );
        }

        PaymentIntent createIntent() {
            return template.execute(status -> service.createIntent("ord-1", "purchase", AMOUNT, "acct-1", "idem-create", "corr-create"));
        }
    }

    private static boolean transactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
