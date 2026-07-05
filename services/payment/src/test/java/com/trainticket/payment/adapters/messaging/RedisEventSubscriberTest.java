package com.trainticket.payment.adapters.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.application.EventEnvelope;
import com.trainticket.payment.application.HandlerResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisEventSubscriberTest {
    @Test
    void recoveredMessageUsesRealPelDeliveryCountBeforeDlq() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FakeRedisStreamOperations streams = new FakeRedisStreamOperations(objectMapper.writeValueAsString(envelope()));
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper);

        streams.deliveryCount = 4;
        subscriber.recoverOnce("events:booking-orchestration", "payment", "payment-test", ignored -> HandlerResult.TRANSIENT_FAILURE);
        assertEquals(1, streams.handledAttempts);
        assertEquals(0, streams.dlqEnvelopes.size());
        assertEquals(0, streams.ackedIds.size());

        streams.deliveryCount = 5;
        subscriber.recoverOnce("events:booking-orchestration", "payment", "payment-test", ignored -> HandlerResult.TRANSIENT_FAILURE);
        assertEquals(1, streams.dlqEnvelopes.size());
        assertEquals(List.of("1-0"), streams.ackedIds);
    }

    @Test
    void deserializesEnvelopeWithoutOptionalCausationId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = """
            {
              "eventId":"evt-0194f2e0-7b3e-7610-0284-5c26e8b0c333",
              "eventType":"SegmentReservationRequested",
              "occurredAt":"2026-07-05T10:30:00Z",
              "correlationId":"corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
              "producer":"booking-orchestration",
              "schemaVersion":1,
              "payload":{
                "segmentBookingId":"sb-1",
                "journeyOrderId":"ord-1",
                "segmentRef":"seg-1",
                "travelerRef":"trav-1",
                "idempotencyKey":"idem-1"
              }
            }
            """;
        FakeRedisStreamOperations streams = new FakeRedisStreamOperations(json);
        RedisEventSubscriber subscriber = new RedisEventSubscriber(streams, objectMapper);

        subscriber.recoverOnce("events:booking-orchestration", "payment", "payment-test", envelope -> {
            assertNull(envelope.causationId());
            return HandlerResult.SUCCESS;
        });

        assertEquals(List.of("1-0"), streams.ackedIds);
        assertEquals(0, streams.dlqEnvelopes.size());
    }

    private static EventEnvelope envelope() {
        return new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            "SegmentReservationRequested",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444",
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c111",
            "booking-orchestration",
            1,
            Map.of("segmentBookingId", "sb-1", "journeyOrderId", "ord-1", "segmentRef", "seg-1", "travelerRef", "trav-1", "idempotencyKey", "idem-1")
        );
    }

    private static final class FakeRedisStreamOperations implements RedisStreamOperations {
        private final String envelopeJson;
        private int deliveryCount;
        private int handledAttempts;
        private final List<String> ackedIds = new ArrayList<>();
        private final List<String> dlqEnvelopes = new ArrayList<>();

        private FakeRedisStreamOperations(String envelopeJson) {
            this.envelopeJson = envelopeJson;
        }

        @Override
        public void createGroup(String stream, String group) {
        }

        @Override
        public List<StreamEntry> readGroup(String stream, String group, String consumerName) {
            return List.of();
        }

        @Override
        public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            return List.of(new StreamEntry("1-0", envelopeJson));
        }

        @Override
        public int deliveryCount(String stream, String group, String messageId) {
            handledAttempts++;
            return deliveryCount;
        }

        @Override
        public void ack(String stream, String group, String messageId) {
            ackedIds.add(messageId);
        }

        @Override
        public void moveToDlq(String stream, String envelopeJson) {
            dlqEnvelopes.add(envelopeJson);
        }
    }
}
