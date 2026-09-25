package com.trainticket.groupbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.groupbooking.Application;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URI", matches = ".+")
class GroupBookingOwningApiLiveTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void owningApiCreatesAndContinuesBookingAfterProcessRestart() throws Exception {
        String schema = "group_api_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                String databaseUrl = System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema;
                String bookingId;
                String[] memberIds;
                String createKey = com.trainticket.platformkit.idempotency.UuidV7.generate();
                String createBody = """
                    {"organizerRef":"org-live","segmentRefs":["seg-live"],"targetTravelerCount":10,
                     "fare":{"currency":"CNY","minorUnits":100000,"discountBasisPoints":500,"negotiationRef":"neg-live"}}
                    """;
                try (ConfigurableApplicationContext first = start(databaseUrl)) {
                    URI base = endpoint(first);
                    JsonNode created = request("POST", base.resolve("/api/v1/group-bookings"), createBody, createKey);
                    bookingId = created.path("groupBookingId").asText();
                    StringBuilder members = new StringBuilder("{\"members\":[");
                    for (int index = 1; index <= 10; index++) {
                        if (index > 1) members.append(',');
                        members.append("{\"travelerRef\":\"tvl-").append(index).append("\",\"maskedDocumentRef\":\"docref-").append(index).append("\"}");
                    }
                    members.append("]}");
                    JsonNode roster = request("PUT", base.resolve("/api/v1/group-bookings/" + bookingId + "/members"), members.toString(), null);
                    assertThat(roster.path("activeMemberCount").asInt()).isEqualTo(10);
                    memberIds = new String[10];
                    for (int index = 0; index < 10; index++) memberIds[index] = roster.path("members").get(index).path("memberId").asText();
                }

                try (ConfigurableApplicationContext restarted = start(databaseUrl)) {
                    URI base = endpoint(restarted);
                    JsonNode replayed = request("POST", base.resolve("/api/v1/group-bookings"), createBody, createKey);
                    assertThat(replayed.path("groupBookingId").asText()).isEqualTo(bookingId);
                    JsonNode restored = request("GET", base.resolve("/api/v1/group-bookings/" + bookingId), null, null);
                    assertThat(restored.path("activeMemberCount").asInt()).isEqualTo(10);
                    assertThat(restored.path("fare").path("discountedMinorUnits").asLong()).isEqualTo(95_000L);
                    JsonNode confirmed = request("POST", base.resolve("/api/v1/group-bookings/" + bookingId + "/confirm"),
                        "{\"capacityHoldId\":\"hold-live\"}", com.trainticket.platformkit.idempotency.UuidV7.generate());
                    assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
                    assertThat(confirmed.path("capacityHoldId").asText()).isEqualTo("hold-live");
                    assertThat(confirmed.path("members")).hasSize(10);
                    assertThat(memberIds).allMatch(id -> id.startsWith("gbm-"));
                }

                try (var rows = admin.createStatement().executeQuery("SELECT count(*) FROM " + schema + ".group_bookings")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = admin.createStatement().executeQuery("SELECT count(*) FROM " + schema + ".outbox")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(12);
                }
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private ConfigurableApplicationContext start(String databaseUrl) {
        return SpringApplication.run(Application.class, "--server.port=0", "--DATABASE_URL=" + databaseUrl,
            "--group-booking.outbox-relay.enabled=false", "--platform.java-kit.redis.enabled=false");
    }

    private static URI endpoint(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    private JsonNode request(String method, URI uri, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return mapper.readTree(response.body());
    }
}
