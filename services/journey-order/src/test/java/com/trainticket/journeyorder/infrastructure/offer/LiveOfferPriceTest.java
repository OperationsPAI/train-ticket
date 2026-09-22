package com.trainticket.journeyorder.infrastructure.offer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.util.UUID;
import java.util.ArrayList;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_OFFER_URL",matches=".+")
class LiveOfferPriceTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_RESOLVE_OFFER_ID",matches=".+")
    void resolvesTheLiveOfferAndItineraryWithoutInventingFacts() throws Exception {
        var mapper=new ObjectMapper();
        var client=RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).baseUrl(System.getenv("TEST_OFFER_URL")).build();
        String id=System.getenv("TEST_RESOLVE_OFFER_ID");
        var source=mapper.readTree(client.get().uri("/api/v1/offers/{id}",id).retrieve().body(String.class));
        var travelers=new ArrayList<String>();
        source.path("passengerMix").path("travelers").forEach(t->travelers.add(t.path("travelerId").asText()));
        var segments=new ArrayList<String>();
        source.path("items").forEach(item->segments.add(item.path("segmentRef").asText()));
        var request=new JourneyOrderRequest(source.path("accountId").asText(),id,source.path("offerVersion").asInt(),travelers,segments);
        var resolved=new HttpOfferPriceAdapter(mapper,RestClient.builder(),System.getenv("TEST_OFFER_URL")).resolve(request);
        assertEquals(source.path("total").path("minorUnits").asLong(),com.trainticket.journeyorder.domain.MonetarySummary.fromItems(resolved.items()).payableTotal().toMinorUnits());
        assertEquals(source.path("passengerMix").path("travelers").get(0).path("travelerType").asText(),resolved.travelers().getFirst().travelerType());
        var itinerary=mapper.readTree(RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).baseUrl(System.getenv("TRIP_PLANNING_URL")).build().get()
            .uri("/api/v1/itineraries/{id}",source.path("itineraryRef").asText()).retrieve().body(String.class));
        assertEquals(itinerary.path("legs").get(0).path("departureTime").asText(),resolved.segments().getFirst().departureTime().toString());
    }
    @Test
    void missingOffersAndStaleVersionsFailWithoutSubstitutingAPrice() {
        var adapter = new HttpOfferPriceAdapter(new ObjectMapper(),RestClient.builder(),System.getenv("TEST_OFFER_URL"));
        var missing = assertThrows(ApiException.class, () -> adapter.totalFor("off-"+UUID.randomUUID(),1));
        assertEquals(ApiErrorCode.NOT_FOUND, missing.code());
        String offer = System.getenv("TEST_OFFER_ID");
        var stale = assertThrows(ApiException.class, () -> adapter.totalFor(offer,Integer.MAX_VALUE));
        assertEquals(ApiErrorCode.PRECONDITION_FAILED,stale.code());
    }
}
