package com.trainticket.groupbooking.application;

import com.trainticket.groupbooking.domain.GroupBooking;
import com.trainticket.groupbooking.domain.GroupMember;
import java.util.List;

public record BookingDetail(
    String groupBookingId,
    String organizerRef,
    List<String> segmentRefs,
    int targetTravelerCount,
    String status,
    String capacityHoldId,
    FareDetail fare,
    int activeMemberCount,
    List<MemberDetail> members
) {
    public static BookingDetail from(GroupBooking booking) {
        return new BookingDetail(
            booking.groupBookingId(),
            booking.organizerRef(),
            booking.segmentRefs(),
            booking.targetTravelerCount(),
            booking.status().name(),
            booking.capacityHoldId(),
            new FareDetail(
                booking.fare().currency(),
                booking.fare().minorUnits(),
                booking.fare().discountBasisPoints(),
                booking.fare().discountedMinorUnits(),
                booking.fare().negotiationRef()
            ),
            booking.activeMemberCount(),
            booking.members().stream().map(MemberDetail::from).toList()
        );
    }

    public record FareDetail(String currency, long minorUnits, int discountBasisPoints, long discountedMinorUnits, String negotiationRef) {}
    public record MemberDetail(String memberId, String travelerRef, String maskedDocumentRef, String status) {
        static MemberDetail from(GroupMember member) {
            return new MemberDetail(member.memberId(), member.travelerRef(), member.maskedDocumentRef(), member.status().name());
        }
    }
}
