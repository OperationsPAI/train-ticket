package com.trainticket.financesettlement.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The authoritative segment booking behind a provider's operational fact.
 *
 * `ProviderReservationConfirmed` names a segment booking and nothing else, so
 * this service attributes it through an index it builds from
 * `SegmentReservationRequested`. Those two events arrive on different streams
 * and are read by different consumer groups, so the confirmation can be handled
 * before the request that would have indexed it -- and a reservation request
 * that has been trimmed off its stream never indexes at all.
 *
 * Measured at revision 495: 21 unattributable cases in ten minutes, each one
 * "missing SegmentReservationRequested index", every one of them a booking
 * booking-orchestration already holds and whose order the local index received a
 * moment later. Reading the booking is the same fallback the waitlist's capacity
 * release resolver uses, for the same reason.
 */
@Component
public class SegmentBookingFactsClient {
    private final RestClient client;
    private final String template;

    public SegmentBookingFactsClient(@Qualifier("financeRestClientBuilder") RestClient.Builder builder,
                                     @Value("${FINANCE_REFERENCE_URL_TEMPLATE:http://%s:8080}") String template) {
        this.template = template;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build();
    }

    /** The journey order the booking belongs to, when booking-orchestration still has it. */
    public Optional<String> orderFor(String segmentBookingId) {
        if (segmentBookingId == null || segmentBookingId.isBlank()) return Optional.empty();
        JsonNode booking;
        try {
            booking = client.get()
                .uri(template.replace("%s", "booking-orchestration") + "/api/v1/internal/segment-bookings/{id}", segmentBookingId)
                .retrieve()
                .body(JsonNode.class);
        } catch (RuntimeException notFound) {
            return Optional.empty();
        }
        if (booking == null) return Optional.empty();
        String orderId = booking.path("journeyOrderId").asText("");
        return orderId.isBlank() ? Optional.empty() : Optional.of(orderId);
    }
}
