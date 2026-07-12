package com.trainticket.travelerprofile.application;

import java.util.Optional;

public interface TravelerProfileStore {
    Optional<TravelerState> findByTravelerId(String travelerId);
    void save(TravelerState state);
}
