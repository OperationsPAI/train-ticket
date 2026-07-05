package com.trainticket.travelerprofile.application;

public class TravelerNotFoundException extends RuntimeException {
    public TravelerNotFoundException(String travelerId) {
        super("Traveler profile not found: " + travelerId);
    }
}
