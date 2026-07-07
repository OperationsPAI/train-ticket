package com.trainticket.travelerprofile.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryTravelerProfileStore implements TravelerProfileStore {
    private final ConcurrentMap<String, TravelerState> travelers = new ConcurrentHashMap<>();
    @Override public Optional<TravelerState> findByTravelerId(String travelerId) { return Optional.ofNullable(travelers.get(travelerId)); }
    @Override public void save(TravelerState state) { travelers.put(state.travelerId(), state); }
}
