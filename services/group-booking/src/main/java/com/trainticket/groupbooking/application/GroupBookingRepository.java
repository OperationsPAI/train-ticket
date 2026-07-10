package com.trainticket.groupbooking.application;

import com.trainticket.groupbooking.domain.GroupBooking;
import java.util.Optional;

public interface GroupBookingRepository {
    void save(GroupBooking booking);
    Optional<GroupBooking> findById(String groupBookingId);
}
