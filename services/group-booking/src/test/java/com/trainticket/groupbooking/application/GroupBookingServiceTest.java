package com.trainticket.groupbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EventPublisher;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupBookingServiceTest {
    private final CapturingPublisher publisher = new CapturingPublisher();
    private final GroupBookingService service = new GroupBookingService(
        Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC),
        new InMemoryGroupBookingRepository(),
        publisher
    );

    @Test
    void createAddAndConfirmPublishesFoundationEvents() {
        BookingDetail created = service.create(new GroupBookingService.CreateGroupBookingCommand(
            "org-1", List.of("seg-1"), 10, "USD", 10_000L, 1_000, "neg-1"), PrefixedIds.newCorrelationId());

        BookingDetail withMembers = service.addMembers(created.groupBookingId(), new GroupBookingService.AddMembersCommand(memberCommands(10)), PrefixedIds.newCorrelationId());
        BookingDetail confirmed = service.confirm(created.groupBookingId(), new GroupBookingService.ConfirmGroupBookingCommand("hold-1"), PrefixedIds.newCorrelationId());

        assertThat(withMembers.activeMemberCount()).isEqualTo(10);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(publisher.eventTypes()).contains("GroupBookingCreated", "GroupMemberAdded", "GroupBookingConfirmed");
    }

    @Test
    void consumesCapacityHoldConfirmedForKnownGroupBooking() {
        BookingDetail created = service.create(new GroupBookingService.CreateGroupBookingCommand(
            "org-1", List.of("seg-1"), 10, "USD", 10_000L, 0, null), PrefixedIds.newCorrelationId());
        service.addMembers(created.groupBookingId(), new GroupBookingService.AddMembersCommand(memberCommands(10)), PrefixedIds.newCorrelationId());

        EventEnvelope envelope = new EventEnvelope(
            PrefixedIds.newEventId(),
            "CapacityHoldConfirmed",
            Instant.parse("2026-07-10T12:01:00Z"),
            PrefixedIds.newCorrelationId(),
            null,
            "capacity-availability",
            1,
            Map.of("groupBookingId", created.groupBookingId(), "holdId", "hold-1", "confirmedQuantity", 10)
        );

        service.handleCapacityHoldConfirmed(envelope);

        BookingDetail detail = service.get(created.groupBookingId());
        assertThat(detail.capacityHoldId()).isEqualTo("hold-1");
        assertThat(detail.status()).isEqualTo("HOLD_ACTIVE");
    }

    private static List<GroupBookingService.MemberCommand> memberCommands(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(index -> new GroupBookingService.MemberCommand("traveler-" + index, "docref-" + index))
            .toList();
    }

    private static final class CapturingPublisher implements EventPublisher {
        private final List<EventEnvelope> events = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            events.add(envelope);
        }

        List<String> eventTypes() {
            return events.stream().map(EventEnvelope::eventType).toList();
        }
    }
}
