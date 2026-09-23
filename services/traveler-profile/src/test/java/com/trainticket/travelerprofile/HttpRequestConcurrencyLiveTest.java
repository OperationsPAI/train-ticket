package com.trainticket.travelerprofile;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.UuidV7;
import jakarta.servlet.Filter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_TRAVELER_SNAPSHOT", matches = ".+")
class HttpRequestConcurrencyLiveTest {
    private static final int REQUESTS = 220;

    @Test
    void incompleteRecordedCreationReplaysDoNotBlockTravelerReadsOrHealth() throws Exception {
        var source = new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("TEST_TRAVELER_SNAPSHOT"))));
        String schema = "traveler_http_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUri = System.getenv("TEST_DATABASE_URI");
        assertNotNull(databaseUri, "TEST_DATABASE_URI is required");
        String redisUri = System.getenv("TEST_REDIS_URL");
        assertNotNull(redisUri, "TEST_REDIS_URL must name a dedicated test Redis database");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var app = new SpringApplication(Application.class, RequestObservation.class).run(
                "--server.port=0",
                "--DATABASE_URL=" + databaseUri + (databaseUri.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                "--REDIS_URL=" + redisUri,
                "--traveler-profile.redis.enabled=false")) {
                var mapper = app.getBean(ObjectMapper.class);
                var jdbc = new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO traveler_profile_snapshots(id,version,data) VALUES(?,?,?::jsonb)",
                    source.required("id").asText(), source.required("version").asLong(), source.required("data").toString());
                var server = (TomcatWebServer) ((ServletWebServerApplicationContext) app).getWebServer();
                var virtual = new CompletableFuture<Boolean>();
                server.getTomcat().getConnector().getProtocolHandler().getExecutor()
                    .execute(() -> virtual.complete(Thread.currentThread().isVirtual()));
                assertTrue(virtual.get(5, TimeUnit.SECONDS), "HTTP executor must use virtual threads");
                String base = "http://127.0.0.1:" + server.getPort();
                String travelerId = source.required("data").required("travelerId").asText();
                var sockets = new ArrayList<Socket>();
                try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2)).build()) {
                    JsonNode recordedRead = get(client, mapper, base, "/api/v1/travelers/" + travelerId);
                    var body = mapper.createObjectNode();
                    for (String field : new String[]{"accountId", "travelerType", "givenName", "familyName", "contactEmail", "contactPhone"}) {
                        body.set(field, source.required("data").required(field));
                    }
                    byte[] bytes = mapper.writeValueAsBytes(body);
                    String key = UuidV7.generate();
                    var create = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/travelers"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                        HttpResponse.BodyHandlers.ofString());
                    assertEquals(201, create.statusCode(), create.body());
                    String createdId = mapper.readTree(create.body()).required("travelerId").asText();
                    JsonNode createdRead = get(client, mapper, base, "/api/v1/travelers/" + createdId);
                    for (String field : new String[]{"accountId", "travelerType", "givenName", "familyName", "contactEmail", "contactPhone"}) {
                        assertEquals(body.get(field), createdRead.get(field), field);
                    }
                    var events = jdbc.queryForList("SELECT event_id,envelope::text AS envelope FROM outbox ORDER BY event_id");
                    assertFalse(events.isEmpty(), "The actual HTTP creation must persist domain events");
                    var entered = app.getBean(CountDownLatch.class);
                    for (int index = 0; index < REQUESTS; index++) {
                        var socket = new Socket("127.0.0.1", server.getPort());
                        sockets.add(socket);
                        socket.setSoTimeout(10_000);
                        String headers = "POST /api/v1/travelers HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Type: application/json\r\nContent-Length: " + bytes.length
                            + "\r\nIdempotency-Key: " + key + "\r\nX-Recorded-Replay: true\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(bytes, 0, 1);
                        socket.getOutputStream().flush();
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "All 220 requests must enter the HTTP filter chain");
                    assertEquals("ok", get(client, mapper, base, "/healthz").required("status").asText());
                    assertEquals("ready", get(client, mapper, base, "/readyz").required("status").asText());
                    assertEquals(recordedRead, get(client, mapper, base, "/api/v1/travelers/" + travelerId));
                    assertEquals(createdRead, get(client, mapper, base, "/api/v1/travelers/" + createdId));
                    for (var socket : sockets) {
                        socket.getOutputStream().write(bytes, 1, bytes.length - 1);
                        socket.getOutputStream().flush();
                    }
                    for (var socket : sockets) {
                        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertTrue(response.startsWith("HTTP/1.1 201"), response);
                        assertTrue(response.endsWith(create.body()), "Every replay must return the original creation response");
                    }
                    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM traveler_profile_snapshots", Integer.class));
                    assertEquals(source.required("data"), mapper.readTree(jdbc.queryForObject(
                        "SELECT data::text FROM traveler_profile_snapshots WHERE id=?", String.class, source.required("id").asText())));
                    assertEquals(source.required("version").asLong(), jdbc.queryForObject(
                        "SELECT version FROM traveler_profile_snapshots WHERE id=?", Long.class, source.required("id").asText()));
                    assertEquals(events, jdbc.queryForList("SELECT event_id,envelope::text AS envelope FROM outbox ORDER BY event_id"));
                    assertEquals(createdRead, get(client, mapper, base, "/api/v1/travelers/" + createdId));
                } finally {
                    for (var socket : sockets) socket.close();
                }
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static JsonNode get(HttpClient client, ObjectMapper mapper, String base, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestObservation {
        @Bean
        CountDownLatch enteredRequests() {
            return new CountDownLatch(REQUESTS);
        }

        @Bean
        FilterRegistrationBean<Filter> observedRequests(CountDownLatch enteredRequests) {
            Filter filter = (request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                if ("true".equals(http.getHeader("X-Recorded-Replay"))) enteredRequests.countDown();
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
