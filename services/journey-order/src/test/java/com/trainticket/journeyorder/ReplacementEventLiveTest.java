package com.trainticket.journeyorder;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_REPLACEMENT_EVENTS", matches = ".+")
class ReplacementEventLiveTest {
    @Test
    void replacementEventsAreAcknowledgedWithoutReenteringPurchaseConfirmation() throws Exception {
        String schema = "order_replacement_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var app = SpringApplication.run(Application.class, "--server.port=0",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema=" + schema,
                "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"))) {
                var mapper = app.getBean(ObjectMapper.class);
                var events = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REPLACEMENT_EVENTS"))));
                var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REPLACEMENT_ORDER"))));
                var jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                String id = snapshot.path("id").asText();
                assertFalse(id.isBlank());
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                    id, snapshot.path("version").asLong(), snapshot.path("data").toString());
                var handler = app.getBean(OrderManagementService.class);
                int count = 0;
                for (var entry : events) {
                    var envelope = mapper.readValue(entry.get(1).get(1).asText(), EventEnvelope.class);
                    var payload = mapper.valueToTree(envelope.payload());
                    if (!envelope.eventType().equals("EntitlementIssued")
                        || !"REPLACEMENT".equals(payload.path("issuePurpose").asText())
                        || !id.equals(payload.path("journeyOrderId").asText())) continue;
                    assertEquals(new EventSubscriber.Success(), handler.handle(envelope));
                    assertEquals(new EventSubscriber.Success(), handler.handle(envelope));
                    count++;
                }
                assertTrue(count > 0, "recorded order must have replacement events");
                assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                assertEquals(snapshot.path("version").asLong(), jdbc.queryForObject("SELECT version FROM journey_order_snapshots WHERE id=?", Long.class, id));
                assertEquals(snapshot.path("data"), mapper.readTree(jdbc.queryForObject("SELECT data::text FROM journey_order_snapshots WHERE id=?", String.class, id)));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
