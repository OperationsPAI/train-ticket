package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.trainticket.financesettlement.domain.Money;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A channel statement is announced twice: generated, then frozen.
 *
 * <p>The freeze used to carry only the transition -- identity, status and
 * frozenAt -- and finance read it as if it restated the whole statement, so
 * every frozen statement was answered `grossPaymentAmount is required` and
 * dead-lettered on its first delivery. Measured on the resident release:
 * `events:payment-channel:dlq` held one entry per channel while the generated
 * rows sat correct in `channel_statements`, and the reconciliation had no
 * channel side at all. These tests hold both halves: the merge, and the
 * freeze-before-generation case that the merge falls back on.
 */
class ChannelStatementProjectionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-03T00:05:00Z"), ZoneOffset.UTC);

    @Test
    void frozenStatementKeepsTheAmountsAndGenerationItsFreezeDoesNotRestate() {
        InMemoryFinanceSettlementProjectionRepository projections = new InMemoryFinanceSettlementProjectionRepository();
        FinanceSettlementEventHandler handler = handler(projections);

        Map<String, Object> generated = statementPayload();
        generated.put("grossPaymentAmount", Map.of("currency", "CNY", "minorUnits", 123456));
        generated.put("grossRefundAmount", Map.of("currency", "CNY", "minorUnits", 4500));
        generated.put("feeAmount", Map.of("currency", "CNY", "minorUnits", 0));
        generated.put("generatedAt", "2026-07-02T00:00:04Z");
        generated.put("status", "GENERATED");
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e201", "ChannelStatementGenerated", generated)));

        Map<String, Object> frozen = statementPayload();
        frozen.put("status", "FROZEN");
        frozen.put("frozenAt", "2026-07-02T00:10:00Z");
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e202", "ChannelStatementFrozen", frozen)));

        ChannelStatementProjection stored = projections.findChannelStatement("chs-1").orElseThrow();
        assertEquals("FROZEN", stored.status());
        assertEquals(Money.of("CNY", "1234.56"), stored.grossPaymentAmount());
        assertEquals(Money.of("CNY", "45.00"), stored.grossRefundAmount());
        assertEquals(Instant.parse("2026-07-02T00:00:04Z"), stored.generatedAt());
        assertEquals(Instant.parse("2026-07-02T00:10:00Z"), stored.frozenAt());
        assertEquals("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e202", stored.sourceEventId());
    }

    @Test
    void aFreezeWhoseGenerationTheConsumerNeverSawIsStillProjected() {
        InMemoryFinanceSettlementProjectionRepository projections = new InMemoryFinanceSettlementProjectionRepository();
        FinanceSettlementEventHandler handler = handler(projections);

        Map<String, Object> frozen = statementPayload();
        frozen.put("status", "FROZEN");
        frozen.put("frozenAt", "2026-07-02T00:10:00Z");
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e203", "ChannelStatementFrozen", frozen)));

        ChannelStatementProjection stored = projections.findChannelStatement("chs-1").orElseThrow();
        assertEquals("FROZEN", stored.status());
        assertNotNull(stored.generatedAt());
        assertEquals(Instant.parse("2026-07-02T00:10:00Z"), stored.generatedAt());
        assertEquals(Money.of("CNY", "0.00"), stored.grossPaymentAmount());
    }

    @Test
    void aGenerationAppliedAfterItsFreezeDoesNotUnfreezeTheStatement() {
        // Both announcements are published in one relay pass, so with more than
        // one consumer they can be applied in either order. The projection is
        // monotonic: a frozen statement stays frozen.
        InMemoryFinanceSettlementProjectionRepository projections = new InMemoryFinanceSettlementProjectionRepository();
        FinanceSettlementEventHandler handler = handler(projections);

        Map<String, Object> frozen = statementPayload();
        frozen.put("status", "FROZEN");
        frozen.put("frozenAt", "2026-07-02T00:10:00Z");
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e211", "ChannelStatementFrozen", frozen)));

        Map<String, Object> generated = statementPayload();
        generated.put("grossPaymentAmount", Map.of("currency", "CNY", "minorUnits", 123456));
        generated.put("generatedAt", "2026-07-02T00:00:04Z");
        generated.put("status", "GENERATED");
        assertEquals(HandlerResult.SUCCESS, handler.handle(envelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0e212", "ChannelStatementGenerated", generated)));

        ChannelStatementProjection stored = projections.findChannelStatement("chs-1").orElseThrow();
        assertEquals("FROZEN", stored.status());
        assertEquals(Instant.parse("2026-07-02T00:10:00Z"), stored.frozenAt());
        assertEquals(Instant.parse("2026-07-02T00:00:04Z"), stored.generatedAt());
        assertEquals(Money.of("CNY", "1234.56"), stored.grossPaymentAmount());
    }

    private static Map<String, Object> statementPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channelStatementId", "chs-1");
        payload.put("channel", "ALIPAY_SIM");
        payload.put("statementDate", "2026-07-02");
        payload.put("currency", "CNY");
        payload.put("seedVersion", "v1");
        payload.put("lineCount", 342648);
        payload.put("statementHash", "sha256:abc");
        return payload;
    }

    private static EventEnvelope envelope(String eventId, String eventType, Map<String, Object> payload) {
        return new EventEnvelope(
            eventId,
            eventType,
            CLOCK.instant(),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0e101",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0e102",
            "payment-channel",
            1,
            payload,
            null,
            null
        );
    }

    private static FinanceSettlementEventHandler handler(FinanceSettlementProjectionRepository projections) {
        return new FinanceSettlementEventHandler(
            new InMemoryConsumedEventLogRepository(),
            new InMemoryPaymentIntentOrderReferenceRepository(),
            new InMemorySegmentBookingOrderReferenceRepository(),
            projections,
            CLOCK,
            null
        );
    }
}
