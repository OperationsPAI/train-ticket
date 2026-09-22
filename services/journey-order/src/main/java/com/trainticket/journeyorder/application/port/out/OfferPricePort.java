package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;

/** Resolves the authoritative price, traveler and itinerary snapshot for an order. */
public interface OfferPricePort {
    ResolvedOrderOffer resolve(JourneyOrderRequest request);
}
