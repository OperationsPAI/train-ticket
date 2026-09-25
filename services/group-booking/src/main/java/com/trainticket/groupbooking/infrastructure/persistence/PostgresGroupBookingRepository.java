package com.trainticket.groupbooking.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.groupbooking.application.GroupBookingRepository;
import com.trainticket.groupbooking.domain.GroupBooking;
import com.trainticket.groupbooking.domain.GroupBookingStatus;
import com.trainticket.groupbooking.domain.GroupFare;
import com.trainticket.groupbooking.domain.GroupMember;
import com.trainticket.groupbooking.domain.GroupMemberStatus;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnExpression("'${DATABASE_URL:}' != ''")
public class PostgresGroupBookingRepository implements GroupBookingRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresGroupBookingRepository(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    @Override
    public void save(GroupBooking booking) {
        jdbc.update(
            """
            INSERT INTO group_bookings(group_booking_id,organizer_ref,segment_refs,target_traveler_count,status,
              fare_currency,fare_minor_units,discount_basis_points,negotiation_ref,capacity_hold_id,cancellation_reason)
            VALUES(?,?,?::jsonb,?,?,?,?,?,?,?,?)
            ON CONFLICT(group_booking_id) DO UPDATE SET
              organizer_ref=EXCLUDED.organizer_ref, segment_refs=EXCLUDED.segment_refs,
              target_traveler_count=EXCLUDED.target_traveler_count, status=EXCLUDED.status,
              fare_currency=EXCLUDED.fare_currency, fare_minor_units=EXCLUDED.fare_minor_units,
              discount_basis_points=EXCLUDED.discount_basis_points, negotiation_ref=EXCLUDED.negotiation_ref,
              capacity_hold_id=EXCLUDED.capacity_hold_id, cancellation_reason=EXCLUDED.cancellation_reason,
              updated_at=now()
            """,
            booking.groupBookingId(), booking.organizerRef(), json(booking.segmentRefs()), booking.targetTravelerCount(),
            booking.status().name(), booking.fare().currency(), booking.fare().minorUnits(), booking.fare().discountBasisPoints(),
            booking.fare().negotiationRef(), booking.capacityHoldId(), booking.cancellationReason()
        );
        for (GroupMember member : booking.members()) {
            jdbc.update(
                """
                INSERT INTO group_members(member_id,group_booking_id,traveler_ref,masked_document_ref,status,added_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(member_id) DO UPDATE SET
                  traveler_ref=EXCLUDED.traveler_ref, masked_document_ref=EXCLUDED.masked_document_ref,
                  status=EXCLUDED.status, added_at=EXCLUDED.added_at
                """,
                member.memberId(), booking.groupBookingId(), member.travelerRef(), member.maskedDocumentRef(),
                member.status().name(), java.sql.Timestamp.from(member.addedAt())
            );
        }
    }

    @Override
    public Optional<GroupBooking> findById(String groupBookingId) {
        return find(groupBookingId, false);
    }

    @Override
    public Optional<GroupBooking> findByIdForUpdate(String groupBookingId) {
        return find(groupBookingId, true);
    }

    private Optional<GroupBooking> find(String groupBookingId, boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        Optional<BookingRow> booking = jdbc.query(
            "SELECT group_booking_id,organizer_ref,segment_refs::text,target_traveler_count,status,fare_currency," +
                "fare_minor_units,discount_basis_points,negotiation_ref,capacity_hold_id,cancellation_reason " +
                "FROM group_bookings WHERE group_booking_id=?" + suffix,
            rs -> rs.next() ? Optional.of(new BookingRow(
                rs.getString("group_booking_id"), rs.getString("organizer_ref"), rs.getString("segment_refs"),
                rs.getInt("target_traveler_count"), rs.getString("status"), rs.getString("fare_currency").trim(),
                rs.getLong("fare_minor_units"), rs.getInt("discount_basis_points"), rs.getString("negotiation_ref"),
                rs.getString("capacity_hold_id"), rs.getString("cancellation_reason"))) : Optional.empty(), groupBookingId
        );
        return booking.map(this::map);
    }

    private GroupBooking map(BookingRow row) {
        String bookingId = row.groupBookingId();
        List<GroupMember> members = jdbc.query(
            "SELECT member_id,traveler_ref,masked_document_ref,status,added_at FROM group_members WHERE group_booking_id=? ORDER BY added_at,member_id",
            (rs, index) -> GroupMember.rehydrate(rs.getString("member_id"), rs.getString("traveler_ref"),
                rs.getString("masked_document_ref"), GroupMemberStatus.valueOf(rs.getString("status")), rs.getTimestamp("added_at").toInstant()),
            bookingId
        );
        try {
            return GroupBooking.rehydrate(bookingId, row.organizerRef(),
                mapper.readValue(row.segmentRefs(), STRING_LIST), row.targetTravelerCount(),
                new GroupFare(row.fareCurrency(), row.fareMinorUnits(), row.discountBasisPoints(), row.negotiationRef()),
                GroupBookingStatus.valueOf(row.status()), row.capacityHoldId(), row.cancellationReason(), members);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("group booking segment refs could not be decoded", error);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("group booking could not be encoded", error); }
    }

    private record BookingRow(String groupBookingId, String organizerRef, String segmentRefs, int targetTravelerCount,
                              String status, String fareCurrency, long fareMinorUnits, int discountBasisPoints,
                              String negotiationRef, String capacityHoldId, String cancellationReason) {}
}
