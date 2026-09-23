package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.Application;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_SEAT_ALLOCATION_EVENTS", matches = ".+")
class SeatAllocationEventLiveTest {
    @Test
    void seatAllocationUsesPersistedBookingReferencesAndReplaysOnce() throws Exception {
        String schema = "booking_seat_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var app = SpringApplication.run(Application.class, "--server.port=0",
                "--booking-orchestration.messaging.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema=" + schema,
                "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"))) {
                var mapper = app.getBean(ObjectMapper.class);
                var events = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_SEAT_ALLOCATION_EVENTS"))));
                var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_SEAT_BOOKING"))));
                var jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                String id = snapshot.path("id").asText();
                assertFalse(id.isBlank());
                jdbc.update("INSERT INTO segment_booking_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                    id, snapshot.path("version").asLong(), snapshot.path("data").toString());
                var handler = app.getBean(BookingOrchestrationService.class);
                int count = 0;
                String allocationId = null;
                for (var entry : events) {
                    var fields = new ArrayList<String>();
                    entry.get(1).forEach(field -> fields.add(field.asText()));
                    var envelope = mapper.readValue(fields.get(fields.indexOf("envelope") + 1), EventEnvelope.class);
                    var payload = mapper.valueToTree(envelope.payload());
                    if (!id.equals(payload.path("segmentBookingId").asText())) continue;
                    assertFalse(payload.has("sagaId"));
                    assertEquals(new HandlerResult.Success(), handler.handleUpstreamEvent(envelope));
                    assertEquals(new HandlerResult.Success(), handler.handleUpstreamEvent(envelope));
                    allocationId = payload.path("seatAllocationId").asText();
                    count++;
                }
                assertEquals(1, count);
                assertEquals(allocationId, jdbc.queryForObject("SELECT data->>'seatAllocationRef' FROM segment_booking_snapshots WHERE id=?", String.class, id));
                assertEquals(snapshot.path("version").asLong() + 1, jdbc.queryForObject("SELECT version FROM segment_booking_snapshots WHERE id=?", Long.class, id));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM booking_saga_snapshots", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
                assertTrue(BookingOrchestrationService.handlesEvent("StandingAssigned"));
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
