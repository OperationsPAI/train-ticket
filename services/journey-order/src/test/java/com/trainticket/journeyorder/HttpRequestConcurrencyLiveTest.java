package com.trainticket.journeyorder;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.idempotency.UuidV7;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

class HttpRequestConcurrencyLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_RISK_ORDER_SNAPSHOT", matches = ".+")
    void waitingHttpRequestsDoNotPreventOrderReadsAndProbes() throws Exception {
        String schema = "order_http_" + UUID.randomUUID().toString().replace("-", "");
        String database = System.getenv("TEST_DATABASE_URL");
        String databaseUri = System.getenv("TEST_DATABASE_URI");
        var mapper = new ObjectMapper();
        var sample = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_RISK_ORDER_SNAPSHOT"))));
        String orderId = sample.path("id").asText();
        var sockets = new ArrayList<Socket>();
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(database)) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class,
                    "--server.port=0",
                    "--DATABASE_URL=" + databaseUri + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                    orderId, sample.path("version").asLong(), sample.path("data").toString());
                var web = (ServletWebServerApplicationContext) context;
                var tomcat = (TomcatWebServer) web.getWebServer();
                var executor = tomcat.getTomcat().getConnector().getProtocolHandler().getExecutor();
                var virtual = new CompletableFuture<Boolean>();
                executor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()));
                assertTrue(virtual.get(5, TimeUnit.SECONDS));
                int port = web.getWebServer().getPort();
                String body = "{\"reason\":\"USER_CANCELLED\"}";
                String key = UuidV7.generate();
                String headers = "POST /api/v1/journey-orders/" + orderId + "/cancel HTTP/1.1\r\n"
                    + "Host: localhost\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                    + "\r\nIdempotency-Key: " + key + "\r\nConnection: close\r\n\r\n";
                for (int index = 0; index < 220; index++) {
                    var socket = new Socket("127.0.0.1", port);
                    socket.setSoTimeout(20000);
                    sockets.add(socket);
                    socket.getOutputStream().write((headers + body.substring(0, 1)).getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
                try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()) {
                    for (String path : new String[]{"/healthz", "/readyz", "/api/v1/journey-orders/" + orderId}) {
                        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(2)).build();
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), response.body());
                        if (path.contains("journey-orders")) assertEquals(orderId, mapper.readTree(response.body()).path("orderId").asText());
                    }
                }
                for (var socket : sockets) {
                    socket.getOutputStream().write(body.substring(1).getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
                for (var socket : sockets) {
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 200"), response);
                }
                assertEquals("CANCELLED", jdbc.queryForObject("SELECT data->>'state' FROM journey_order_snapshots WHERE id=?", String.class, orderId));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='JourneyOrderCancelled'", Integer.class));
            } finally {
                for (var socket : sockets) socket.close();
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
