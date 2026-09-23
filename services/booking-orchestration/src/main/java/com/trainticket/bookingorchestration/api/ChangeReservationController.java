package com.trainticket.bookingorchestration.api;

import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/internal/change-reservations")
public class ChangeReservationController {
    private final BookingOrchestrationService service;
    private final RestClient client;
    private final String cases, orders, offers, fares, trips;

    public ChangeReservationController(BookingOrchestrationService service, RestClient.Builder builder,
        @Value("${POST_SALES_BASE_URL:http://post-sales:8080}") String cases,
        @Value("${JOURNEY_ORDER_BASE_URL:http://journey-order:8080}") String orders,
        @Value("${OFFER_MANAGEMENT_BASE_URL:http://offer-management:8080}") String offers,
        @Value("${FARE_PRICING_BASE_URL:http://fare-pricing:8080}") String fares,
        @Value("${TRIP_PLANNING_BASE_URL:http://trip-planning:8080}") String trips) {
        this.service=service; this.cases=cases; this.orders=orders; this.offers=offers; this.fares=fares;
        this.trips=trips;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(5));
        this.client=builder.requestFactory(factory).build();
    }

    @PostMapping
    public BookingOrchestrationService.SegmentBookingDetail reserve(@RequestBody Request request,
        @RequestHeader("X-Correlation-Id") String correlation, @RequestHeader("Idempotency-Key") String key) {
        JsonNode change=get(cases,"/api/v1/post-sales-cases/"+request.caseId(),correlation);
        if (!request.caseId().equals(change.path("caseId").asText())) fail("change response has another case reference");
        if (!"CHANGE".equals(change.path("caseType").asText()) || !"APPROVED".equals(change.path("status").asText())) fail("change must be approved before reserving replacement capacity");
        JsonNode order=get(orders,"/api/v1/journey-orders/"+change.path("journeyOrderId").asText(),correlation);
        if (!order.path("riskClear").asBoolean() || !"CONFIRMED".equals(order.path("status").asText())) fail("original order must be confirmed with risk clearance");
        String offerId=change.path("targetOfferId").asText();
        if (offerId.isBlank()) fail("change has no selected offer");
        JsonNode offer=get(offers,"/api/v1/offers/"+offerId,correlation);
        if (!offerId.equals(offer.path("offerId").asText())) fail("target offer response has another reference");
        if (!order.path("accountId").asText().equals(offer.path("accountId").asText())) fail("target offer account mismatch");
        var travelers=new HashSet<String>();
        offer.path("passengerMix").path("travelers").forEach(value -> travelers.add(value.path("travelerId").asText()));
        var scoped=new HashSet<String>(); change.path("scope").path("travelerRefs").forEach(value -> scoped.add(value.asText()));
        if (!travelers.equals(scoped) || !scoped.contains(request.travelerRef())) fail("replacement travelers mismatch");
        JsonNode item=null;
        for (var candidate : offer.path("items")) if (request.segmentRef().equals(candidate.path("segmentRef").asText())) item=candidate;
        if (item==null) fail("replacement segment is absent from selected offer");
        JsonNode quote=get(fares,"/api/v1/fare-quotes/"+offer.path("fareQuoteRef").asText(),correlation);
        if (!offer.path("fareQuoteRef").asText().equals(quote.path("quoteId").asText())) fail("target fare response has another reference");
        String seatClass=quote.path("breakdown").path("seatClassMultiplier").path("seatClass").asText();
        if (seatClass.isBlank()) fail("target fare quote has no seat class");
        JsonNode itinerary=get(trips,"/api/v1/itineraries/"+offer.path("itineraryRef").asText(),correlation);
        if(!offer.path("itineraryRef").asText().equals(itinerary.path("itineraryRef").asText()))fail("target itinerary response has another reference");
        JsonNode leg=null;
        for(var candidate:itinerary.path("legs"))if(request.segmentRef().equals(candidate.path("serviceSegmentRef").asText()))leg=candidate;
        if(leg==null || leg.path("servicePlanRef").asText().isBlank())fail("target segment has no scheduled service");
        String date=java.time.Instant.parse(leg.path("departureTime").asText()).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();
        return service.requestChangeReservation(request.caseId(),order.path("orderId").asText(),item.path("offerItemId").asText(),
            request.segmentRef(),request.travelerRef(),seatClass,leg.path("servicePlanRef").asText(),date,correlation);
    }
    private JsonNode get(String base,String path,String correlation) {
        JsonNode result=client.get().uri(base+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change lookup returned "+response.getStatusCode());
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
        });
        if (result==null) throw new IllegalStateException("missing change reference response");
        return result;
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingOrchestrationService.SegmentBookingDetail cancel(@PathVariable String bookingId,
        @RequestBody CancelRequest request,@RequestHeader("X-Correlation-Id")String correlation) {
        JsonNode change=get(cases,"/api/v1/post-sales-cases/"+request.caseId(),correlation);
        if(!request.caseId().equals(change.path("caseId").asText()) || !"CHANGE".equals(change.path("caseType").asText())
            || !change.path("changeOperations").has("payment-terminated"))fail("replacement cancellation requires terminated change payment");
        var names=change.path("changeOperations").fieldNames();
        while(names.hasNext()) {
            String name=names.next();
            if(name.startsWith("ticket:") || name.startsWith("void:") || name.equals("order-applied"))fail("replacement cancellation cannot follow ticket changes");
        }
        return service.cancelUnpaidChangeReservation(request.caseId(),change.path("journeyOrderId").asText(),bookingId,correlation);
    }
    private static void fail(String message) { throw new BookingOrchestrationService.PreconditionFailedException(message,null); }
    @ExceptionHandler(BookingOrchestrationService.PreconditionFailedException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,String>> precondition(BookingOrchestrationService.PreconditionFailedException error) {
        return org.springframework.http.ResponseEntity.status(412).body(java.util.Map.of("code","PRECONDITION_FAILED","message",error.getMessage()));
    }
    public record Request(String caseId,String segmentRef,String travelerRef) {}
    public record CancelRequest(String caseId) {}
}
