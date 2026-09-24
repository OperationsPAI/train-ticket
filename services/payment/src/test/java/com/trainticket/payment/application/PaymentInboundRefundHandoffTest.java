package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.LatePaymentCase;
import com.trainticket.payment.domain.LatePaymentCaseStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inbound event path hands a routed refund to the channel outside every transaction,
 * and commits the event deduplication record only together with the applied channel result.
 */
class PaymentInboundRefundHandoffTest {
    private static final Instant FIXED_NOW = Instant.parse("2026-07-05T10:30:00Z");
    private static final Money AMOUNT = Money.fromMinorUnits(1_200, "CNY");
    private static final String CASE_REF = "case-1";
    private static final ChannelRef ORIGINAL_ROUTE = new ChannelRef("ALIPAY", "co-1", null, "txn-1", null, null, null);

    @Test
    void inboundRefundHandsOffOutsideTheTransactionAndRecordsTheEventOnce() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createCapturedIntent();
        String eventId = PrefixedIds.newEventId();
        EventEnvelope approval = postSalesApproved(eventId, intentId);

        assertEquals(HandlerResult.SUCCESS, fixture.handler.handle(approval));

        assertEquals(1, fixture.channel.refundHandoffs.size());
        assertFalse(fixture.channel.refundHandoffs.getFirst().transactionActive(), "the channel call must run outside every database transaction");
        List<Refund> refunds = fixture.refunds.findByBusinessCaseRef(CASE_REF);
        assertEquals(1, refunds.size());
        assertEquals(RefundStatus.SUBMITTED, refunds.getFirst().status());
        assertEquals(1, fixture.publisher.published().stream().filter(envelope -> "RefundRequested".equals(envelope.eventType())).count());
        assertTrue(fixture.deduplicator.isProcessed(eventId));

        assertEquals(HandlerResult.SUCCESS, fixture.handler.handle(approval));
        assertEquals(1, fixture.channel.refundHandoffs.size(), "a duplicate delivery must not submit to the channel again");
        assertEquals(1, fixture.refunds.findByBusinessCaseRef(CASE_REF).size());
    }

    @Test
    void inboundRefundRetrySubmitsToTheChannelAgainWhenTheApplyFails() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createCapturedIntent();
        String eventId = PrefixedIds.newEventId();
        EventEnvelope approval = postSalesApproved(eventId, intentId);
        fixture.refunds.failNextSubmittedSave();

        assertEquals(HandlerResult.TRANSIENT_FAILURE, fixture.handler.handle(approval));

        assertFalse(fixture.deduplicator.isProcessed(eventId), "a failed effect must leave no deduplication record");
        assertEquals(1, fixture.channel.refundHandoffs.size());
        assertEquals(RefundStatus.REQUESTED, fixture.refunds.findByBusinessCaseRef(CASE_REF).getFirst().status());

        assertEquals(HandlerResult.SUCCESS, fixture.handler.handle(approval));

        assertEquals(2, fixture.channel.refundHandoffs.size(), "the retry must submit to the channel again");
        assertTrue(fixture.deduplicator.isProcessed(eventId));
        List<Refund> refunds = fixture.refunds.findByBusinessCaseRef(CASE_REF);
        assertEquals(1, refunds.size());
        assertEquals(RefundStatus.SUBMITTED, refunds.getFirst().status());
        assertEquals(1, refunds.getFirst().attemptCount());
        assertEquals(1, fixture.publisher.published().stream().filter(envelope -> "RefundRequested".equals(envelope.eventType())).count());
    }

    @Test
    void latePaymentRefundHandsOffOutsideTheTransaction() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createLatePaymentCase();
        Refund requested = fixture.commands.prepareLatePaymentRefund(intentId, "ord-late", "ancillary:ord-late", "LATE_PAYMENT", "idem-late", "corr-late");
        assertEquals(RefundStatus.REQUESTED, requested.status());

        Refund settled = fixture.commands.submitLatePaymentRefund(intentId, "corr-submit");

        assertEquals(RefundStatus.SETTLED, settled.status());
        assertEquals(1, fixture.channel.refundHandoffs.size());
        assertFalse(fixture.channel.refundHandoffs.getFirst().transactionActive(), "the channel call must run outside every database transaction");
        LatePaymentCase lateCase = fixture.lateCases.findById(LatePaymentCase.caseId(intentId, ORIGINAL_ROUTE.channel(), ORIGINAL_ROUTE.channelTransactionId())).orElseThrow();
        assertEquals(LatePaymentCaseStatus.RESOLVED, lateCase.status());
    }

    private static EventEnvelope postSalesApproved(String eventId, String paymentIntentId) {
        return new EventEnvelope(
            eventId,
            "PostSalesApproved",
            FIXED_NOW.plusSeconds(60),
            PrefixedIds.newCorrelationId(),
            PrefixedIds.newEventId(),
            "post-sales",
            1,
            Map.of(
                "caseId", CASE_REF,
                "orderId", "ord-late",
                "approvedActions", Map.of(
                    "paymentIntentId", paymentIntentId,
                    "refundAmount", Map.of("currency", "CNY", "minorUnits", AMOUNT.toMinorUnits())
                )
            )
        );
    }

    record HandoffCall(boolean transactionActive) {
    }

    private static final class RecordingChannelClient implements PaymentChannelClient {
        private final List<HandoffCall> refundHandoffs = new CopyOnWriteArrayList<>();

        @Override
        public HandoffOrder handoffCapture(PaymentIntent intent, String orderIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef requestedRef) {
            ChannelRef ref = new ChannelRef(requestedRef.channel(), "co-" + suffix(orderIdempotencyKey), null, "txn-" + suffix(submitIdempotencyKey), null, null, null);
            return new HandoffOrder(ref.channelOrderId(), "SUCCEEDED", ref.channelTransactionId(), ref);
        }

        @Override
        public HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String refundIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef originalRoute) {
            refundHandoffs.add(new HandoffCall(TransactionSynchronizationManager.isActualTransactionActive()));
            ChannelRef ref = new ChannelRef(originalRoute.channel(), originalRoute.channelOrderId(), "cr-" + suffix(refundIdempotencyKey),
                originalRoute.channelTransactionId(), "rftxn-" + suffix(submitIdempotencyKey), null, null);
            return new HandoffRefund(ref.channelRefundId(), "SUCCEEDED", ref.channelRefundTransactionId(), ref);
        }

        private static String suffix(String key) {
            return key.substring(key.length() - 8);
        }
    }

    /**
     * Stores refund snapshots the way the PostgreSQL repository does, so an effect that
     * fails before its commit leaves the previously stored refund state in place.
     */
    private static final class SnapshotRefundRepository implements RefundRepository {
        private final Map<String, Refund> stored = new LinkedHashMap<>();
        private boolean failNextSubmittedSave;

        void failNextSubmittedSave() {
            failNextSubmittedSave = true;
        }

        @Override
        public Optional<Refund> findById(String refundId) {
            return Optional.ofNullable(stored.get(refundId)).map(SnapshotRefundRepository::copy);
        }

        @Override
        public List<Refund> findByBusinessCaseRef(String businessCaseRef) {
            return stored.values().stream()
                .filter(refund -> refund.sourceCaseRef().equals(businessCaseRef))
                .map(SnapshotRefundRepository::copy)
                .toList();
        }

        @Override
        public void save(Refund refund) {
            if (failNextSubmittedSave && refund.status() == RefundStatus.SUBMITTED) {
                failNextSubmittedSave = false;
                throw new IllegalStateException("refund write failed");
            }
            stored.put(refund.refundId(), copy(refund));
        }

        private static Refund copy(Refund refund) {
            return Refund.rehydrate(
                    refund.refundId(), refund.paymentIntentId(), refund.amount(), refund.sourceCaseRef(), refund.reasonCode(),
                    refund.idempotencyKey(), refund.status(), refund.channelRefundTransactionId(), refund.attemptCount(),
                    refund.domainEvents(), refund.channelRef(), refund.channelRefundIdempotencyKey(), refund.channelRefundSubmitIdempotencyKey())
                .withVersion(refund.version());
        }
    }

    private static final class Fixture {
        final FakeEventPublisher publisher = new FakeEventPublisher();
        final ConsumedEventDeduplicator deduplicator = new ConsumedEventDeduplicator();
        final InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        final SnapshotRefundRepository refunds = new SnapshotRefundRepository();
        final InMemoryLatePaymentCaseRepository lateCases = new InMemoryLatePaymentCaseRepository();
        final RecordingChannelClient channel = new RecordingChannelClient();
        final PaymentChannelHandoffTransactionTest.SerializingTransactionManager transactions =
            new PaymentChannelHandoffTransactionTest.SerializingTransactionManager();
        final TransactionTemplate template = new TransactionTemplate(transactions);
        final PaymentCommandService commands;
        final PaymentInboundEventHandler handler;

        Fixture() {
            commands = new PaymentCommandService(
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                publisher,
                intents,
                refunds,
                new InMemoryReservationPaymentRequestRepository(),
                lateCases,
                channel,
                new ChannelRouter(List.of(new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 40))),
                Optional.of(transactions)
            );
            handler = new PaymentInboundEventHandler(deduplicator, commands, Optional.of(transactions));
        }

        String createCapturedIntent() {
            PaymentIntent intent = template.execute(status -> commands.createIntent("ord-late", "purchase", AMOUNT, "acct-1", "idem-create", "corr-create"));
            template.executeWithoutResult(status -> commands.captureIntent(intent.paymentIntentId(), "idem-capture", "corr-capture"));
            return intent.paymentIntentId();
        }

        String createLatePaymentCase() {
            String intentId = "pi-late-refund";
            PaymentIntent intent = PaymentIntent.rehydrate(
                intentId, "ord-late", "purchase", AMOUNT, "acct-1", FIXED_NOW.plusSeconds(30), "idem-create",
                PaymentIntentStatus.EXPIRED, Money.fromMinorUnits(0, "CNY"), AMOUNT, Money.fromMinorUnits(0, "CNY"), Set.of(), List.of(),
                ORIGINAL_ROUTE, null, null, List.of());
            LatePaymentCase lateCase = LatePaymentCase.rehydrate(
                LatePaymentCase.caseId(intentId, ORIGINAL_ROUTE.channel(), ORIGINAL_ROUTE.channelTransactionId()),
                intentId, AMOUNT, ORIGINAL_ROUTE.channel(), ORIGINAL_ROUTE.channelTransactionId(), "late collection",
                FIXED_NOW, LatePaymentCaseStatus.OPEN, null);
            intents.save(intent);
            lateCases.save(lateCase);
            return intentId;
        }
    }
}
