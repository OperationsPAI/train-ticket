package com.trainticket.groupbooking.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class GroupBooking {
    public static final int MINIMUM_GROUP_SIZE = 10;

    private final String groupBookingId;
    private final String organizerRef;
    private final List<String> segmentRefs;
    private final int targetTravelerCount;
    private final GroupFare fare;
    private final LinkedHashMap<String, GroupMember> members;
    private final List<GroupBookingEvent> domainEvents;
    private GroupBookingStatus status;
    private String capacityHoldId;
    private String cancellationReason;

    private GroupBooking(
        String groupBookingId,
        String organizerRef,
        List<String> segmentRefs,
        int targetTravelerCount,
        GroupFare fare,
        GroupBookingStatus status
    ) {
        this.groupBookingId = GroupMember.requireText(groupBookingId, "groupBookingId");
        this.organizerRef = GroupMember.requireText(organizerRef, "organizerRef");
        this.segmentRefs = List.copyOf(validateSegmentRefs(segmentRefs));
        if (targetTravelerCount < MINIMUM_GROUP_SIZE) {
            throw new DomainRuleViolation("targetTravelerCount must be at least " + MINIMUM_GROUP_SIZE);
        }
        this.targetTravelerCount = targetTravelerCount;
        this.fare = Objects.requireNonNull(fare, "fare is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.members = new LinkedHashMap<>();
        this.domainEvents = new ArrayList<>();
    }

    public static GroupBooking create(
        String groupBookingId,
        String organizerRef,
        List<String> segmentRefs,
        int targetTravelerCount,
        GroupFare fare,
        Instant now
    ) {
        GroupBooking booking = new GroupBooking(groupBookingId, organizerRef, segmentRefs, targetTravelerCount, fare, GroupBookingStatus.DRAFT);
        booking.domainEvents.add(new GroupBookingCreated(groupBookingId, booking.organizerRef, booking.segmentRefs, targetTravelerCount, fare, Objects.requireNonNull(now, "now is required")));
        return booking;
    }

    public static GroupBooking rehydrate(
        String groupBookingId,
        String organizerRef,
        List<String> segmentRefs,
        int targetTravelerCount,
        GroupFare fare,
        GroupBookingStatus status,
        String capacityHoldId,
        String cancellationReason,
        List<GroupMember> members
    ) {
        GroupBooking booking = new GroupBooking(groupBookingId, organizerRef, segmentRefs, targetTravelerCount, fare, status);
        booking.capacityHoldId = GroupFare.blankToNull(capacityHoldId);
        booking.cancellationReason = GroupFare.blankToNull(cancellationReason);
        for (GroupMember member : members) {
            booking.members.put(member.memberId(), member);
        }
        return booking;
    }

    public void addMembers(List<GroupMember> newMembers, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (terminal()) {
            throw new DomainRuleViolation("cannot add members to " + status + " group booking");
        }
        if (newMembers == null || newMembers.isEmpty()) {
            throw new DomainRuleViolation("members are required");
        }
        LinkedHashMap<String, GroupMember> stagedMembers = new LinkedHashMap<>(members);
        for (GroupMember member : newMembers) {
            Objects.requireNonNull(member, "member is required");
            if (stagedMembers.containsKey(member.memberId())) {
                throw new DomainRuleViolation("duplicate memberId in group booking");
            }
            if (activeTravelerExists(stagedMembers, member.travelerRef())) {
                throw new DomainRuleViolation("duplicate travelerRef in active group booking roster");
            }
            stagedMembers.put(member.memberId(), member);
        }
        for (GroupMember member : newMembers) {
            members.put(member.memberId(), member);
            domainEvents.add(new GroupMemberAdded(groupBookingId, member.memberId(), member.travelerRef(), now));
        }
    }

    public void confirm(String capacityHoldId, Instant now) {
        if (terminal()) {
            throw new DomainRuleViolation("cannot confirm " + status + " group booking");
        }
        String holdId = GroupMember.requireText(capacityHoldId, "capacityHoldId");
        int activeCount = activeMemberCount();
        if (activeCount < MINIMUM_GROUP_SIZE) {
            throw new DomainRuleViolation("at least 10 active members are required before confirmation");
        }
        this.capacityHoldId = holdId;
        this.status = GroupBookingStatus.CONFIRMED;
        domainEvents.add(new GroupBookingConfirmed(groupBookingId, holdId, activeCount, activeMemberIds(), Objects.requireNonNull(now, "now is required")));
    }

    public void recordCapacityHoldConfirmed(String capacityHoldId, int confirmedQuantity, Instant now) {
        String holdId = GroupMember.requireText(capacityHoldId, "capacityHoldId");
        if (confirmedQuantity < MINIMUM_GROUP_SIZE) {
            throw new DomainRuleViolation("confirmedQuantity must be at least 10 for a group booking");
        }
        if (confirmedQuantity < activeMemberCount()) {
            throw new DomainRuleViolation("confirmedQuantity cannot be less than active roster size");
        }
        if (status == GroupBookingStatus.DRAFT) {
            status = GroupBookingStatus.HOLD_ACTIVE;
        }
        this.capacityHoldId = holdId;
    }

    public void cancel(List<String> memberIds, String reason, Instant now) {
        if (status == GroupBookingStatus.CANCELLED) {
            return;
        }
        String normalizedReason = GroupMember.requireText(reason, "reason");
        List<String> cancelled;
        if (memberIds == null || memberIds.isEmpty()) {
            if (status == GroupBookingStatus.CONFIRMED) {
                throw new DomainRuleViolation("confirmed group booking requires memberIds for partial cancellation");
            }
            for (GroupMember member : members.values()) {
                member.cancel();
            }
            status = GroupBookingStatus.CANCELLED;
            cancellationReason = normalizedReason;
            cancelled = new ArrayList<>(members.keySet());
        } else {
            cancelled = new ArrayList<>();
            for (String memberId : memberIds) {
                GroupMember member = members.get(GroupMember.requireText(memberId, "memberId"));
                if (member == null) {
                    throw new DomainRuleViolation("member not found: " + memberId);
                }
                member.cancel();
                cancelled.add(member.memberId());
            }
            if (activeMemberCount() < MINIMUM_GROUP_SIZE) {
                if (status == GroupBookingStatus.CONFIRMED) {
                    throw new DomainRuleViolation("partial cancellation would reduce confirmed group below minimum size");
                }
                status = GroupBookingStatus.CANCELLED;
                cancellationReason = "group size below minimum after partial cancellation: " + normalizedReason;
            } else {
                status = GroupBookingStatus.PARTIALLY_CANCELLED;
            }
        }
        domainEvents.add(new GroupBookingCancelled(groupBookingId, normalizedReason, List.copyOf(cancelled), Objects.requireNonNull(now, "now is required")));
    }

    public List<GroupBookingEvent> pullEvents() {
        List<GroupBookingEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public String groupBookingId() { return groupBookingId; }
    public String organizerRef() { return organizerRef; }
    public List<String> segmentRefs() { return segmentRefs; }
    public int targetTravelerCount() { return targetTravelerCount; }
    public GroupFare fare() { return fare; }
    public GroupBookingStatus status() { return status; }
    public String capacityHoldId() { return capacityHoldId; }
    public String cancellationReason() { return cancellationReason; }
    public List<GroupMember> members() { return List.copyOf(members.values()); }
    public int activeMemberCount() { return (int) members.values().stream().filter(GroupMember::active).count(); }

    private static boolean activeTravelerExists(LinkedHashMap<String, GroupMember> roster, String travelerRef) {
        return roster.values().stream().anyMatch(member -> member.active() && member.travelerRef().equals(travelerRef));
    }

    private boolean terminal() {
        return status == GroupBookingStatus.CONFIRMED || status == GroupBookingStatus.CANCELLED;
    }

    private List<String> activeMemberIds() {
        return members.values().stream().filter(GroupMember::active).map(GroupMember::memberId).toList();
    }

    private static List<String> validateSegmentRefs(List<String> segmentRefs) {
        if (segmentRefs == null || segmentRefs.isEmpty()) {
            throw new DomainRuleViolation("segmentRefs are required");
        }
        List<String> normalized = new ArrayList<>();
        for (String segmentRef : segmentRefs) {
            normalized.add(GroupMember.requireText(segmentRef, "segmentRef"));
        }
        return normalized;
    }
}
