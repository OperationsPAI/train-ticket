package com.trainticket.adminaudit.adapters.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.adminaudit.application.AdminAuditService;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.adminaudit.domain.ManualActionRequested;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagingAdapterTest {
    @Test
    void wrapsDomainEventInCanonicalEnvelope() {
        com.trainticket.platformkit.messaging.EventEnvelope domainEnvelope = new com.trainticket.platformkit.messaging.EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0a111",
            "ManualActionRequested",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0a222",
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0a333",
            "admin-audit",
            1,
            java.util.Map.of()
        );
        ManualActionRequested event = new ManualActionRequested(
            domainEnvelope,
            "ma-1",
            "payment",
            "RefundPayment",
            "pi-1",
            "CUSTOMER_REQUEST",
            "refund",
            "op-1",
            "Ops",
            true
        );

        EventEnvelope envelope = AdminAuditService.toContractEnvelope(event);

        assertThat(envelope.eventId()).startsWith("evt-");
        assertThat(envelope.correlationId()).startsWith("corr-");
        assertThat(envelope.causationId()).startsWith("cmd-");
        assertThat(envelope.producer()).isEqualTo("admin-audit");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.payload()).isInstanceOfSatisfying(java.util.Map.class, payload ->
            assertThat(payload).containsEntry("manualActionId", "ma-1"));
    }

    @Test
    void subscriberDeduplicatesDuplicateEventId() {
        InMemoryConsumedEventLog log = new InMemoryConsumedEventLog();
        List<String> handled = new ArrayList<>();
        DeduplicatingEventHandler handler = new DeduplicatingEventHandler(log, envelope -> {
            handled.add(envelope.eventId());
            return EventSubscriber.HandlerResult.SUCCESS;
        });
        EventEnvelope envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0a444",
            "AnythingHappened",
            Instant.parse("2026-07-05T10:30:00Z"),
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0a555",
            "evt-0194f2e0-7b3e-7610-8284-5c26e8b0a666",
            "payment",
            1,
            java.util.Map.of()
        );

        assertThat(handler.handle(envelope)).isEqualTo(EventSubscriber.HandlerResult.SUCCESS);
        assertThat(handler.handle(envelope)).isEqualTo(EventSubscriber.HandlerResult.SUCCESS);

        assertThat(handled).containsExactly("evt-0194f2e0-7b3e-7610-8284-5c26e8b0a444");
    }
}
