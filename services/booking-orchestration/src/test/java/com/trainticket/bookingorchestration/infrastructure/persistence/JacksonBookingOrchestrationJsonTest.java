package com.trainticket.bookingorchestration.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class JacksonBookingOrchestrationJsonTest {
    @Test
    void snapshotSerializesBusinessFieldsAtTopLevel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("businessField", "value");

        String json = objectMapper.writeValueAsString(new JacksonBookingOrchestrationJson.BookingSagaSnapshot(data));
        JsonNode root = objectMapper.readTree(json);

        assertEquals("value", root.path("businessField").asText());
        assertTrue(root.has("businessField"));
        assertFalse(root.has("data"));
    }

    @Test
    void segmentSnapshotPreservesStandingAcceptance() {
        SegmentBooking booking = SegmentBooking.requestReservation("sb-1", "ord-1", "ofi-1", "seg-1",
            "tvl-1", "purchase", Clock.systemUTC()).withClassRef("SECOND_CLASS")
            .withStandingAcceptance(true).withSupplyReference("ss-1", "2026-09-25");

        var snapshot = JacksonBookingOrchestrationJson.segmentSnapshot(booking, "saga-1", new ObjectMapper());
        SegmentBooking restored = JacksonBookingOrchestrationJson.toSegmentBooking(snapshot, new ObjectMapper());

        assertTrue(restored.standingAccepted());
        assertEquals("PURCHASE_ORDER", restored.purchaseSourceType());
        assertEquals("ord-1", restored.purchaseSourceRef());
    }
}
