package com.trainticket.groupbooking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupBookingTest {
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    void requiresAtLeastTenTravelersForGroupBooking() {
        assertThatThrownBy(() -> GroupBooking.create("gb-1", "org-1", List.of("seg-1"), 9, fare(), NOW))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("at least 10");
    }

    @Test
    void addsMembersAndRejectsDuplicateActiveTraveler() {
        GroupBooking booking = GroupBooking.create("gb-1", "org-1", List.of("seg-1"), 12, fare(), NOW);
        booking.pullEvents();

        booking.addMembers(List.of(GroupMember.active("mem-1", "traveler-1", "docref-1", NOW)), NOW);

        assertThat(booking.activeMemberCount()).isEqualTo(1);
        assertThat(booking.pullEvents()).hasOnlyElementsOfType(GroupMemberAdded.class);
        assertThatThrownBy(() -> booking.addMembers(List.of(GroupMember.active("mem-2", "traveler-1", "docref-2", NOW)), NOW))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("duplicate travelerRef");
    }

    @Test
    void rejectsDuplicateTravelerWithinSameAdditionWithoutPartialMutation() {
        GroupBooking booking = GroupBooking.create("gb-1", "org-1", List.of("seg-1"), 12, fare(), NOW);
        booking.pullEvents();

        assertThatThrownBy(() -> booking.addMembers(List.of(
            GroupMember.active("mem-1", "traveler-1", "docref-1", NOW),
            GroupMember.active("mem-2", "traveler-1", "docref-2", NOW)
        ), NOW))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("duplicate travelerRef");

        assertThat(booking.activeMemberCount()).isZero();
        assertThat(booking.pullEvents()).isEmpty();
    }

    @Test
    void confirmsOnlyWhenActiveRosterMeetsMinimumSize() {
        GroupBooking booking = GroupBooking.create("gb-1", "org-1", List.of("seg-1"), 10, fare(), NOW);
        booking.addMembers(members(10), NOW);
        booking.pullEvents();

        booking.confirm("hold-1", NOW);

        assertThat(booking.status()).isEqualTo(GroupBookingStatus.CONFIRMED);
        assertThat(booking.capacityHoldId()).isEqualTo("hold-1");
        assertThat(booking.pullEvents()).singleElement().isInstanceOf(GroupBookingConfirmed.class);
    }

    @Test
    void partialCancellationCancelsGroupWhenRosterDropsBelowMinimum() {
        GroupBooking booking = GroupBooking.create("gb-1", "org-1", List.of("seg-1"), 10, fare(), NOW);
        booking.addMembers(members(10), NOW);
        booking.pullEvents();

        booking.cancel(List.of("mem-1"), "traveler withdrew", NOW);

        assertThat(booking.status()).isEqualTo(GroupBookingStatus.CANCELLED);
        assertThat(booking.activeMemberCount()).isEqualTo(9);
    }

    private static GroupFare fare() {
        return new GroupFare("USD", 12_500L, 750, "neg-1");
    }

    private static List<GroupMember> members(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(index -> GroupMember.active("mem-" + index, "traveler-" + index, "docref-" + index, NOW))
            .toList();
    }
}
