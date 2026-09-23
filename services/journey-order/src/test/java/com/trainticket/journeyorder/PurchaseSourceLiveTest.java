package com.trainticket.journeyorder;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_PURCHASE_SOURCE_ORDER", matches = ".+")
class PurchaseSourceLiveTest {
    @Test
    void originalAcceptedOfferRemainsReadableAfterRecordedChange() throws Exception {
        String schema = "purchase_source_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var app = SpringApplication.run(Application.class, "--server.port=0",
                "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"))) {
                var mapper = app.getBean(ObjectMapper.class);
                var sample = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PURCHASE_SOURCE_ORDER"))));
                var data = sample.path("data");
                String id = sample.path("id").asText();
                var created = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                boolean changed = false;
                for (var fact : data.path("timeline")) {
                    if ("JourneyOrderCreated".equals(fact.path("factType").asText())) created.add(fact);
                    if ("ChangeApplied".equals(fact.path("factType").asText())) changed = true;
                }
                assertEquals(1, created.size());
                assertTrue(changed, "recorded source must include an applied change");
                String originalOffer = created.getFirst().path("attributes").path("offerId").asText();
                assertFalse(originalOffer.isBlank());
                assertNotEquals(originalOffer, data.path("offerSnapshot").path("offerId").asText());
                var jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                    id, sample.path("version").asLong(), data.toString());
                int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
                String base = "http://127.0.0.1:" + port + "/api/v1/internal/journey-orders/";
                try (var client = HttpClient.newHttpClient()) {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        var response = client.send(HttpRequest.newBuilder(URI.create(base + id + "/purchase-source"))
                            .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), response.body());
                        var result = mapper.readTree(response.body());
                        assertEquals(id, result.path("orderId").asText());
                        assertEquals(data.path("accountId"), result.path("accountId"));
                        assertEquals(originalOffer, result.path("offerId").asText());
                        assertEquals(created.getFirst().path("factId"), result.path("timelineFactId"));
                        assertEquals(java.time.Instant.parse(created.getFirst().path("occurredAt").asText()),
                            java.time.Instant.parse(result.path("acceptedAt").asText()));
                    }
                    var missing = client.send(HttpRequest.newBuilder(URI.create(base + "ord-" + UUID.randomUUID() + "/purchase-source"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(404, missing.statusCode(), missing.body());
                }
                assertEquals(data, mapper.readTree(jdbc.queryForObject("SELECT data::text FROM journey_order_snapshots WHERE id=?", String.class, id)));
                assertEquals(sample.path("version").asLong(), jdbc.queryForObject("SELECT version FROM journey_order_snapshots WHERE id=?", Long.class, id));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
