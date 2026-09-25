package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.adapters.paymentchannel.HttpPaymentChannelClient;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentIntentStatus;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.payment.domain.ports.RefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryLatePaymentCaseRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

class OrphanRefundRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");
    private static final String CASE_ID = "case-orphan-1";
    private static final String PAYMENT_ID = "pi-orphan-1";
    private static final String REFUND_ID = "rf-orphan-1";
    private static final String CHANNEL_REFUND_ID = "cr-orphan-1";
    private static final String CHANNEL_REFUND_TRANSACTION_ID = "rtx-orphan-1";
    private static final Money AMOUNT = Money.fromMinorUnits(1_200, "CNY");
    private static final ChannelRef ROUTE = new ChannelRef("ALIPAY", "co-orphan-1", null, "txn-orphan-1", null, null, null);

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer postSales;
    private String postSalesBaseUrl;

    @BeforeEach
    void startPostSales() throws Exception {
        postSales = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        postSales.createContext("/api/v1/post-sales-cases/" + CASE_ID, exchange -> {
            byte[] body = approvedCase().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        postSales.start();
        postSalesBaseUrl = "http://127.0.0.1:" + postSales.getAddress().getPort();
    }

    @AfterEach
    void stopPostSales() {
        postSales.stop(0);
    }

    @Test
    void concurrentRecoveryReusesCommittedIntentAndSettlesOnce() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch concurrentSubmissions = new CountDownLatch(2);
        when(fixture.channel.refundFacts(CHANNEL_REFUND_ID)).thenReturn(channelFacts("CREATED"));
        when(fixture.channel.resumeRefund(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            fixture.calls.add(new ChannelCall(
                invocation.getArgument(1),
                TransactionSynchronizationManager.isActualTransactionActive(),
                fixture.refunds.hasCommittedKeys(REFUND_ID, invocation.getArgument(1))
            ));
            concurrentSubmissions.countDown();
            assertTrue(concurrentSubmissions.await(10, TimeUnit.SECONDS));
            return succeededHandoff();
        });

        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = workers.submit(() -> {
                start.await();
                return fixture.recovery.recover(CASE_ID, CHANNEL_REFUND_ID);
            });
            var second = workers.submit(() -> {
                start.await();
                return fixture.recovery.recover(CASE_ID, CHANNEL_REFUND_ID);
            });
            start.countDown();

            assertEquals(RefundStatus.SETTLED, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(RefundStatus.SETTLED, second.get(10, TimeUnit.SECONDS).status());
        }

        assertEquals(2, fixture.calls.size());
        assertEquals(1, fixture.calls.stream().map(ChannelCall::submitKey).distinct().count());
        assertTrue(fixture.calls.stream().allMatch(call -> !call.transactionActive()));
        assertTrue(fixture.calls.stream().allMatch(ChannelCall::keysCommitted));
        Refund stored = fixture.refunds.findById(REFUND_ID).orElseThrow();
        assertEquals(RefundStatus.SETTLED, stored.status());
        assertEquals(1, stored.attemptCount());
        assertEquals(1, fixture.publisher.count("RefundSettled"));
        assertEquals(1, fixture.publisher.count("PaymentRefunded"));
    }

    @Test
    void retryAfterCrashFollowingChannelSuccessUsesPersistedIntent() {
        Fixture fixture = new Fixture();
        AtomicBoolean channelSucceeded = new AtomicBoolean();
        when(fixture.channel.refundFacts(CHANNEL_REFUND_ID)).thenAnswer(invocation ->
            channelFacts(channelSucceeded.get() ? "SUCCEEDED" : "CREATED"));
        when(fixture.channel.resumeRefund(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            fixture.calls.add(new ChannelCall(
                invocation.getArgument(1),
                TransactionSynchronizationManager.isActualTransactionActive(),
                fixture.refunds.hasCommittedKeys(REFUND_ID, invocation.getArgument(1))
            ));
            channelSucceeded.set(true);
            fixture.transactions.failNextBegin();
            return succeededHandoff();
        });

        assertThrows(CannotCreateTransactionException.class,
            () -> fixture.recovery.recover(CASE_ID, CHANNEL_REFUND_ID));

        Refund afterCrash = fixture.refunds.findById(REFUND_ID).orElseThrow();
        assertEquals(RefundStatus.REQUESTED, afterCrash.status());
        assertFalse(afterCrash.channelRefundSubmitIdempotencyKey().isBlank());
        assertEquals(1, fixture.calls.size());
        assertTrue(fixture.calls.getFirst().keysCommitted());
        assertFalse(fixture.calls.getFirst().transactionActive());

        Refund recovered = fixture.recovery.recover(CASE_ID, CHANNEL_REFUND_ID);

        assertEquals(RefundStatus.SETTLED, recovered.status());
        assertEquals(CHANNEL_REFUND_TRANSACTION_ID, recovered.channelRefundTransactionId());
        assertEquals(1, fixture.calls.size(), "the retry observes channel success instead of submitting again");
        assertEquals(1, fixture.publisher.count("RefundSettled"));
        assertEquals(1, fixture.publisher.count("PaymentRefunded"));
    }

    private JsonNode approvedCase() {
        return mapper.valueToTree(Map.of(
            "caseId", CASE_ID,
            "status", "APPLIED",
            "caseType", "REFUND",
            "journeyOrderId", "ord-orphan-1",
            "decision", Map.of("refundableAmount", Map.of("currency", "CNY", "minorUnits", AMOUNT.toMinorUnits()))
        ));
    }

    private JsonNode channelFacts(String status) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("channelRefundId", CHANNEL_REFUND_ID);
        facts.put("refundId", REFUND_ID);
        facts.put("paymentIntentId", PAYMENT_ID);
        facts.put("channelOrderId", ROUTE.channelOrderId());
        facts.put("originalChannelTransactionId", ROUTE.channelTransactionId());
        facts.put("channel", ROUTE.channel());
        facts.put("amount", Map.of("currency", "CNY", "minorUnits", AMOUNT.toMinorUnits()));
        facts.put("refundReasonCode", "CUSTOMER_REQUEST");
        facts.put("correlationId", "corr-orphan-1");
        facts.put("status", status);
        facts.put("version", 1);
        facts.put("requestFingerprint", "fp-orphan-1");
        if ("SUCCEEDED".equals(status)) {
            facts.put("channelRefundTransactionId", CHANNEL_REFUND_TRANSACTION_ID);
        }
        return mapper.valueToTree(facts);
    }

    private static PaymentChannelClient.HandoffRefund succeededHandoff() {
        ChannelRef ref = ROUTE.withRefund(CHANNEL_REFUND_ID).withRefundTransaction(CHANNEL_REFUND_TRANSACTION_ID);
        return new PaymentChannelClient.HandoffRefund(CHANNEL_REFUND_ID, "SUCCEEDED", CHANNEL_REFUND_TRANSACTION_ID, ref);
    }

    private record ChannelCall(String submitKey, boolean transactionActive, boolean keysCommitted) {
    }

    private static final class SnapshotRefundRepository implements RefundRepository {
        private final Map<String, Refund> stored = new LinkedHashMap<>();

        @Override
        public synchronized Optional<Refund> findById(String refundId) {
            return Optional.ofNullable(stored.get(refundId)).map(SnapshotRefundRepository::copy);
        }

        @Override
        public synchronized List<Refund> findByBusinessCaseRef(String businessCaseRef) {
            return stored.values().stream()
                .filter(refund -> refund.sourceCaseRef().equals(businessCaseRef))
                .map(SnapshotRefundRepository::copy)
                .toList();
        }

        @Override
        public synchronized void save(Refund refund) {
            Refund copy = copy(refund);
            copy.withVersion(refund.version() + 1);
            refund.withVersion(copy.version());
            stored.put(refund.refundId(), copy);
        }

        synchronized boolean hasCommittedKeys(String refundId, String submitKey) {
            Refund refund = stored.get(refundId);
            return refund != null && submitKey.equals(refund.channelRefundSubmitIdempotencyKey());
        }

        private static Refund copy(Refund refund) {
            return Refund.rehydrate(
                    refund.refundId(), refund.paymentIntentId(), refund.amount(), refund.sourceCaseRef(), refund.reasonCode(),
                    refund.idempotencyKey(), refund.status(), refund.channelRefundTransactionId(), refund.attemptCount(),
                    refund.domainEvents(), refund.channelRef(), refund.channelRefundIdempotencyKey(), refund.channelRefundSubmitIdempotencyKey())
                .withVersion(refund.version());
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            events.add(envelope);
        }

        long count(String eventType) {
            return events.stream().filter(event -> eventType.equals(event.eventType())).count();
        }
    }

    private static final class FailingTransactionManager extends AbstractPlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean failNextBegin = new AtomicBoolean();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (failNextBegin.compareAndSet(true, false)) {
                throw new CannotCreateTransactionException("simulated crash before result apply");
            }
            lock.lock();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            lock.unlock();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            lock.unlock();
        }

        void failNextBegin() {
            failNextBegin.set(true);
        }
    }

    private final class Fixture {
        final InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        final SnapshotRefundRepository refunds = new SnapshotRefundRepository();
        final RecordingPublisher publisher = new RecordingPublisher();
        final FailingTransactionManager transactions = new FailingTransactionManager();
        final HttpPaymentChannelClient channel = mock(HttpPaymentChannelClient.class);
        final List<ChannelCall> calls = new CopyOnWriteArrayList<>();
        final OrphanRefundRecoveryService recovery;

        Fixture() {
            PaymentIntent intent = PaymentIntent.rehydrate(
                PAYMENT_ID, "ord-orphan-1", "purchase", AMOUNT, "acct-orphan-1", NOW.plusSeconds(60), "idem-orphan-1",
                PaymentIntentStatus.CAPTURED, Money.zero(AMOUNT.currency()), AMOUNT, Money.zero(AMOUNT.currency()),
                Set.of(ROUTE.channelTransactionId()), List.of(), ROUTE, null, null, List.of());
            intents.save(intent);
            PaymentCommandService commands = new PaymentCommandService(
                Clock.fixed(NOW, ZoneOffset.UTC), publisher, intents, refunds,
                new InMemoryReservationPaymentRequestRepository(), new InMemoryLatePaymentCaseRepository(), channel,
                new ChannelRouter(List.of(new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 40))),
                Optional.of(transactions));
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            recovery = new OrphanRefundRecoveryService(
                commands, refunds, channel, RestClient.builder().requestFactory(requestFactory), mapper,
                postSalesBaseUrl, Optional.of(transactions));
        }
    }
}
