package com.trainticket.groupbooking.application;

import com.trainticket.groupbooking.domain.GroupBooking;
import com.trainticket.groupbooking.domain.GroupBookingCancelled;
import com.trainticket.groupbooking.domain.GroupBookingConfirmed;
import com.trainticket.groupbooking.domain.GroupBookingCreated;
import com.trainticket.groupbooking.domain.GroupBookingEvent;
import com.trainticket.groupbooking.domain.GroupFare;
import com.trainticket.groupbooking.domain.GroupMember;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EventEnvelopeFactory;
import com.trainticket.platformkit.messaging.EventPublisher;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GroupBookingService {
    private static final String PRODUCER = "group-booking";

    private final Clock clock;
    private final GroupBookingRepository repository;
    private final EventPublisher publisher;
    private final EventEnvelopeFactory envelopeFactory;

    @Autowired
    public GroupBookingService(GroupBookingRepository repository, EventPublisher publisher) {
        this(Clock.systemUTC(), repository, publisher);
    }

    public GroupBookingService(Clock clock, GroupBookingRepository repository, EventPublisher publisher) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.publisher = Objects.requireNonNull(publisher, "publisher is required");
        this.envelopeFactory = new EventEnvelopeFactory(PRODUCER, clock);
    }

    public BookingDetail create(CreateGroupBookingCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        GroupFare fare = new GroupFare(command.currency(), command.minorUnits(), command.discountBasisPoints(), command.negotiationRef());
        GroupBooking booking = GroupBooking.create(
            "gb-" + UuidV7.generate(),
            command.organizerRef(),
            command.segmentRefs(),
            command.targetTravelerCount(),
            fare,
            clock.instant()
        );
        repository.save(booking);
        publish(booking.pullEvents(), normalizedCorrelationId(correlationId), PrefixedIds.newCommandId());
        return BookingDetail.from(booking);
    }

    public BookingDetail addMembers(String groupBookingId, AddMembersCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        GroupBooking booking = getAggregate(groupBookingId);
        Instant now = clock.instant();
        List<GroupMember> members = command.members().stream()
            .map(member -> GroupMember.active("gbm-" + UuidV7.generate(), member.travelerRef(), member.maskedDocumentRef(), now))
            .toList();
        booking.addMembers(members, now);
        repository.save(booking);
        publish(booking.pullEvents(), normalizedCorrelationId(correlationId), PrefixedIds.newCommandId());
        return BookingDetail.from(booking);
    }

    public BookingDetail confirm(String groupBookingId, ConfirmGroupBookingCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        GroupBooking booking = getAggregate(groupBookingId);
        booking.confirm(command.capacityHoldId(), clock.instant());
        repository.save(booking);
        publish(booking.pullEvents(), normalizedCorrelationId(correlationId), PrefixedIds.newCommandId());
        return BookingDetail.from(booking);
    }

    public BookingDetail cancel(String groupBookingId, CancelGroupBookingCommand command, String correlationId) {
        Objects.requireNonNull(command, "command is required");
        GroupBooking booking = getAggregate(groupBookingId);
        booking.cancel(command.memberIds(), command.reason(), clock.instant());
        repository.save(booking);
        publish(booking.pullEvents(), normalizedCorrelationId(correlationId), PrefixedIds.newCommandId());
        return BookingDetail.from(booking);
    }

    public BookingDetail get(String groupBookingId) {
        return BookingDetail.from(getAggregate(groupBookingId));
    }

    public void handleCapacityHoldConfirmed(EventEnvelope envelope) {
        if (!"CapacityHoldConfirmed".equals(envelope.eventType())) {
            return;
        }
        Map<?, ?> payload = envelope.payload() instanceof Map<?, ?> map ? map : Map.of();
        String holdId = text(payload.get("holdId"));
        if (holdId == null) {
            throw new IllegalArgumentException("CapacityHoldConfirmed requires holdId");
        }
        requireText(payload, "inventoryPoolId");
        requireText(payload, "capacityUnitRef");
        if (!(payload.get("interval") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("CapacityHoldConfirmed requires interval");
        }
        requireText(payload, "confirmedAt");

        GroupBooking booking = repository.findByCapacityHoldId(holdId)
            .orElseThrow(() -> new NotFoundException("group booking not found for capacity hold: " + holdId));
        booking.recordCapacityHoldConfirmed(holdId, clock.instant());
        repository.save(booking);
    }

    private GroupBooking getAggregate(String groupBookingId) {
        return repository.findById(groupBookingId)
            .orElseThrow(() -> new NotFoundException("group booking not found: " + groupBookingId));
    }

    private void publish(List<GroupBookingEvent> events, String correlationId, String causationId) {
        for (GroupBookingEvent event : events) {
            publisher.publish(envelopeFactory.create(event.eventType(), correlationId, causationId, payload(event)));
        }
    }

    private static Object payload(GroupBookingEvent event) {
        if (event instanceof GroupBookingCreated created) {
            return new GroupBookingCreatedPayload(
                created.groupBookingId(),
                created.organizerRef(),
                created.segmentRefs(),
                created.targetTravelerCount(),
                new MoneyPayload(created.fare().currency(), created.fare().minorUnits()),
                created.fare().discountBasisPoints(),
                created.fare().negotiationRef()
            );
        }
        if (event instanceof com.trainticket.groupbooking.domain.GroupMemberAdded added) {
            return new GroupMemberAddedPayload(added.groupBookingId(), added.memberId(), added.travelerRef());
        }
        if (event instanceof GroupBookingConfirmed confirmed) {
            return new GroupBookingConfirmedPayload(confirmed.groupBookingId(), confirmed.capacityHoldId(), confirmed.confirmedTravelerCount(), confirmed.memberIds());
        }
        if (event instanceof GroupBookingCancelled cancelled) {
            return new GroupBookingCancelledPayload(cancelled.groupBookingId(), cancelled.reason(), cancelled.cancelledMemberIds());
        }
        throw new IllegalArgumentException("unsupported event: " + event.eventType());
    }

    private static String normalizedCorrelationId(String correlationId) {
        return PrefixedIds.isCorrelationId(correlationId) ? correlationId : PrefixedIds.newCorrelationId();
    }

    private static String requireText(Map<?, ?> payload, String field) {
        String value = text(payload.get(field));
        if (value == null) {
            throw new IllegalArgumentException("CapacityHoldConfirmed requires " + field);
        }
        return value;
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    public record CreateGroupBookingCommand(
        String organizerRef,
        List<String> segmentRefs,
        int targetTravelerCount,
        String currency,
        long minorUnits,
        int discountBasisPoints,
        String negotiationRef
    ) {}

    public record MemberCommand(String travelerRef, String maskedDocumentRef) {}
    public record AddMembersCommand(List<MemberCommand> members) {}
    public record ConfirmGroupBookingCommand(String capacityHoldId) {}
    public record CancelGroupBookingCommand(List<String> memberIds, String reason) {}
    public record MoneyPayload(String currency, long minorUnits) {}
    public record GroupBookingCreatedPayload(String groupBookingId, String organizerRef, List<String> segmentRefs, int targetTravelerCount, MoneyPayload fare, int discountBasisPoints, String negotiationRef) {}
    public record GroupMemberAddedPayload(String groupBookingId, String memberId, String travelerRef) {}
    public record GroupBookingConfirmedPayload(String groupBookingId, String capacityHoldId, int confirmedTravelerCount, List<String> memberIds) {}
    public record GroupBookingCancelledPayload(String groupBookingId, String reason, List<String> cancelledMemberIds) {}
}
