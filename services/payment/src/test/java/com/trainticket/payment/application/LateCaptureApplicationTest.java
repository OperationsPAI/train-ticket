package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.LatePaymentCase;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.PaymentIntentStatus;
import com.trainticket.payment.infrastructure.persistence.InMemoryLatePaymentCaseRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A channel-confirmed collection that arrives after its intent has gone terminal must be RECORDED,
 * never discarded.
 *
 * The intent window is 30 seconds and the timeout sweeper runs every second, so a
 * {@code ChannelOrderSucceeded} delayed behind any real backlog arrives against an already-EXPIRED
 * intent. Before {@link PaymentCommandService#captureIntentFromChannel} routed that to a late
 * payment case, {@code intent.capture()} threw
 * {@link com.trainticket.payment.domain.DomainRuleViolation}, the inbound handler classified it
 * FATAL and the collection was dead-lettered.
 */
class LateCaptureApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-05T10:30:00Z");
    private static final Money COLLECTED = Money.fromMinorUnits(10_750, "CNY");

    @Test
    void channelCaptureAgainstAnExpiredIntentOpensALatePaymentCaseInsteadOfThrowing() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createdIntent("ord-late-expired");
        fixture.expire(intentId);

        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-late-1", "co-late-1", "evt-late-1", "corr-late-1");

        LatePaymentCase lateCase = fixture.singleLateCase();
        assertEquals(intentId, lateCase.paymentIntentId());
        assertEquals("ALIPAY", lateCase.channel());
        assertEquals("txn-late-1", lateCase.channelTransactionId());
        assertEquals(COLLECTED, lateCase.capturedAmount());

        EventEnvelope detected = fixture.onlyEventOfType("LatePaymentDetected");
        Map<?, ?> payload = (Map<?, ?>) detected.payload();
        assertEquals(lateCase.latePaymentCaseId(), payload.get("latePaymentCaseId"));
        assertEquals(intentId, payload.get("paymentIntentId"));
        assertEquals(Map.of("currency", "CNY", "minorUnits", 10_750L), payload.get("capturedAmount"));
    }

    /**
     * The intent must stay terminal. A late capture is a money discrepancy for finance to resolve,
     * not a resurrection: flipping the intent to CAPTURED here would let a cancelled order issue a
     * ticket (docs/02-domains/payment.md 6.3).
     */
    @Test
    void lateCaptureLeavesTheIntentTerminalAndPublishesNoPaymentCaptured() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createdIntent("ord-late-cancelled");
        fixture.commands.cancelIntent(intentId, "customer changed mind", "idem-cancel", "corr-cancel");

        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-late-2", "co-late-2", "evt-late-2", "corr-late-2");

        assertEquals(PaymentIntentStatus.CANCELLED, fixture.commands.getIntent(intentId).status());
        assertTrue(
            fixture.publisher.published().stream().noneMatch(e -> "PaymentCaptured".equals(e.eventType())),
            "a late capture must not publish PaymentCaptured");
        assertNotNull(fixture.singleLateCase());
    }

    /**
     * {@code ChannelOrderSucceeded} and the reconciliation-driven
     * {@code ChannelOrderRecoveryDetected} describe the same money under DIFFERENT event ids, so
     * consumer-side eventId dedup cannot collapse them. The case id is folded from the channel
     * transaction precisely so the second report lands on the case that already exists -- otherwise
     * one payment opens two cases and an operator resolving one leaves the other open.
     */
    @Test
    void twoReportsOfOneLateCollectionResolveToASingleCase() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createdIntent("ord-late-twice");
        fixture.expire(intentId);

        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-late-3", "co-late-3", "evt-succeeded", "corr-late-3");
        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-late-3", "co-late-3", "evt-recovery-detected", "corr-late-3");

        assertEquals(1, fixture.lateCases.findAll().size(), "one collection must not open two cases");
        assertEquals(
            1,
            fixture.publisher.published().stream().filter(e -> "LatePaymentDetected".equals(e.eventType())).count(),
            "the second report must not republish LatePaymentDetected");
    }

    /** An already-CAPTURED intent is a duplicate callback, not a late capture: still a no-op. */
    @Test
    void capturedIntentIsStillTreatedAsADuplicateRatherThanALateCapture() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createdIntent("ord-captured");
        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-ok", "co-ok", "evt-ok", "corr-ok");

        fixture.commands.captureIntentFromChannel(
            intentId, COLLECTED, "ALIPAY", "txn-ok", "co-ok", "evt-ok-again", "corr-ok");

        assertEquals(PaymentIntentStatus.CAPTURED, fixture.commands.getIntent(intentId).status());
        assertTrue(fixture.lateCases.findAll().isEmpty(), "a duplicate callback must not open a late payment case");
    }

    /** End to end: the inbound handler must ack rather than dead-letter a confirmed collection. */
    @Test
    void inboundChannelOrderSucceededForAnExpiredIntentIsAckedRatherThanDeadLettered() {
        Fixture fixture = new Fixture();
        String intentId = fixture.createdIntent("ord-late-inbound");
        fixture.expire(intentId);
        PaymentInboundEventHandler handler =
            new PaymentInboundEventHandler(new ConsumedEventDeduplicator(), fixture.commands);

        HandlerResult result = handler.handle(new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d001",
            "ChannelOrderSucceeded",
            NOW.plusSeconds(3_600),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0d002",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0d003",
            "payment-channel",
            1,
            Map.of(
                "paymentIntentId", intentId,
                "channel", "ALIPAY",
                "channelOrderId", "co-inbound",
                "channelTransactionId", "txn-inbound",
                "succeededAmount", Map.of("currency", "CNY", "minorUnits", 10_750L),
                "status", "SUCCEEDED"
            )
        ));

        assertEquals(HandlerResult.SUCCESS, result, "a channel-confirmed collection must never be dead-lettered");
        assertEquals(intentId, fixture.singleLateCase().paymentIntentId());
    }

    private static final class Fixture {
        private final FakeEventPublisher publisher = new FakeEventPublisher();
        private final InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        private final InMemoryLatePaymentCaseRepository lateCases = new InMemoryLatePaymentCaseRepository();
        private final AdvanceableClock clock = new AdvanceableClock(NOW);
        private final PaymentCommandService commands;

        private Fixture() {
            this.commands = new PaymentCommandService(
                clock,
                publisher,
                intents,
                new InMemoryRefundRepository(),
                new InMemoryReservationPaymentRequestRepository(),
                lateCases,
                new NoopPaymentChannelClient(),
                new ChannelRouter(List.of(new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 40)))
            );
        }

        private String createdIntent(String businessRef) {
            return commands.createIntent(
                businessRef, "purchase", COLLECTED, "acct-1", "idem-" + businessRef, "corr-" + businessRef)
                .paymentIntentId();
        }

        /**
         * Drives the intent to EXPIRED through the real path -- advance past the channel's 30s
         * window, then run the timeout sweeper's command -- rather than constructing that state.
         */
        private void expire(String intentId) {
            clock.advanceSeconds(3_600);
            commands.expireDuePaymentIntents("payment-timeout-sweeper", "corr-sweeper", 100);
            assertEquals(PaymentIntentStatus.EXPIRED, commands.getIntent(intentId).status());
        }

        private LatePaymentCase singleLateCase() {
            List<LatePaymentCase> all = lateCases.findAll();
            assertEquals(1, all.size(), "exactly one late payment case expected");
            return all.getFirst();
        }

        private EventEnvelope onlyEventOfType(String eventType) {
            List<EventEnvelope> matching = publisher.published().stream()
                .filter(envelope -> eventType.equals(envelope.eventType()))
                .toList();
            assertEquals(1, matching.size(), "exactly one " + eventType + " expected");
            return matching.getFirst();
        }
    }

    /** A clock the test can move forward, so intent expiry happens through the real code path. */
    private static final class AdvanceableClock extends Clock {
        private Instant instant;

        private AdvanceableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
