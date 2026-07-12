package com.trainticket.groupbooking.application;

import com.trainticket.groupbooking.domain.GroupBooking;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryGroupBookingRepository implements GroupBookingRepository {
    private final ConcurrentMap<String, GroupBooking> bookings = new ConcurrentHashMap<>();

    @Override
    public void save(GroupBooking booking) {
        bookings.put(booking.groupBookingId(), booking);
    }

    @Override
    public Optional<GroupBooking> findById(String groupBookingId) {
        return Optional.ofNullable(bookings.get(groupBookingId));
    }
}
