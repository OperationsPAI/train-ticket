package com.trainticket.travelerprofile.application;

import java.util.Optional;

public interface TravelerProfileStore {
    Optional<TravelerState> findByTravelerId(String travelerId);
    default Optional<TravelerState> findByTravelerIdForUpdate(String travelerId) { return findByTravelerId(travelerId); }
    void save(TravelerState state);
}
