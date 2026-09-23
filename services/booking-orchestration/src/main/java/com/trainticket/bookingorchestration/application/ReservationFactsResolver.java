package com.trainticket.bookingorchestration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public final class ReservationFactsResolver {
    private final RestClient client;
    private final String baseTemplate;
    private final ObjectMapper mapper;

    public ReservationFactsResolver(RestClient.Builder builder, ObjectMapper mapper,
        @Value("${BOOKING_FACTS_BASE_URL_TEMPLATE:http://%s:8080}") String baseTemplate) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build();
        this.mapper = mapper;
        this.baseTemplate = baseTemplate;
    }

    public Facts resolve(String orderId, String segment, String traveler, String correlation) {
        JsonNode order = get("journey-order", "/api/v1/journey-orders/" + orderId, correlation);
        require(orderId.equals(text(order, "orderId")), "order reference differs");
        require(!Set.of("CANCELLED", "CLOSED", "FAILED", "REFUNDED").contains(text(order, "status")), "order cannot accept reservations");
        require(strings(order.path("travelerRefs")).contains(traveler), "traveler is outside order");
        require(strings(order.path("segmentRefs")).contains(segment), "segment is outside order");
        String offerId = text(order, "offerId");
        JsonNode offer = get("offer-management", "/api/v1/offers/" + offerId, correlation);
        require(offerId.equals(text(offer, "offerId")), "offer reference differs");
        require(text(order, "accountId").equals(text(offer, "accountId")), "offer account differs");
        JsonNode item = null;
        for (JsonNode candidate : offer.path("items")) {
            if (segment.equals(candidate.path("segmentRef").asText())) {
                require(item == null, "offer contains repeated segment items");
                item = candidate;
            }
        }
        require(item != null, "segment is absent from offer");
        var passengers = new HashSet<String>();
        offer.path("passengerMix").path("travelers").forEach(value -> passengers.add(text(value, "travelerId")));
        require(passengers.equals(strings(order.path("travelerRefs"))), "offer travelers differ from order");
        String quoteId = text(offer, "fareQuoteRef");
        JsonNode quote = get("fare-pricing", "/api/v1/fare-quotes/" + quoteId, correlation);
        require(quoteId.equals(text(quote, "quoteId")), "fare quote reference differs");
        String seatClass = text(quote.path("breakdown").path("seatClassMultiplier"), "seatClass");
        require(Set.of("SECOND_CLASS", "FIRST_CLASS", "BUSINESS_CLASS", "STANDING", "SLEEPER_HARD", "SLEEPER_SOFT").contains(seatClass), "fare quote class is unsupported");
        String itineraryId = text(offer, "itineraryRef");
        JsonNode itinerary = get("trip-planning", "/api/v1/itineraries/" + itineraryId, correlation);
        require(itineraryId.equals(text(itinerary, "itineraryRef")), "itinerary reference differs");
        JsonNode leg = null;
        for (JsonNode candidate : itinerary.path("legs")) {
            if (segment.equals(candidate.path("serviceSegmentRef").asText())) {
                require(leg == null, "itinerary contains repeated segment legs");
                leg = candidate;
            }
        }
        require(leg != null, "segment is absent from itinerary");
        String service = text(leg, "servicePlanRef");
        Instant departure = Instant.parse(text(leg, "departureTime"));
        boolean matched = false;
        for (JsonNode snapshot : order.path("segments")) {
            if (segment.equals(snapshot.path("segmentRef").asText())) {
                require(departure.equals(Instant.parse(text(snapshot, "departureTime"))), "itinerary departure differs from accepted order");
                require(text(leg, "originStopRef").equals(text(snapshot, "origin")) && text(leg, "destinationStopRef").equals(text(snapshot, "destination")), "itinerary endpoints differ from accepted order");
                matched = true;
            }
        }
        require(matched, "accepted order has no segment snapshot");
        return new Facts(orderId, segment, traveler, text(item, "offerItemId"), seatClass, service, departure.atOffset(ZoneOffset.UTC).toLocalDate().toString());
    }

    private JsonNode get(String service, String path, String correlation) {
        return client.get().uri(baseTemplate.formatted(service) + path).header("X-Correlation-Id", correlation).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("reservation facts " + service + " returned " + response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
    }
    private static Set<String> strings(JsonNode values) {
        require(values.isArray() && !values.isEmpty(), "reference collection is missing");
        var result = new HashSet<String>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.asText().isBlank(), "reference collection contains an invalid value");
            require(result.add(value.asText()), "reference collection contains a duplicate");
        }
        return result;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.asText().isBlank(), field + " is required");
        return value.asText();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BookingOrchestrationService.PreconditionFailedException(message, null);
    }
    public record Facts(String orderId, String segmentRef, String travelerRef, String offerItemRef,
                        String classRef, String scheduledServiceRef, String serviceDate) {}
}
