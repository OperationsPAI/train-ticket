package com.trainticket.travelerprofile.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class TravelerNotFoundException extends ApiException {
    public TravelerNotFoundException(String travelerId) {
        super(ApiErrorCode.NOT_FOUND, "traveler profile not found: " + travelerId);
    }
}
