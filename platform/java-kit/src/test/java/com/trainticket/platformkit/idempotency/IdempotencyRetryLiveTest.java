package com.trainticket.platformkit.idempotency;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.CanonicalErrorWriter;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyRetryLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL", matches=".+")
    void sameKeyRecoversFromDatabaseOutageAndCachedServerFailure() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(4);
        String schema = "idem_retry_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = new HikariDataSource(config)) {
            var adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var pool = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(pool);
                jdbc.execute("CREATE TABLE idempotency_records(key text PRIMARY KEY,request_hash text NOT NULL,status_code int NOT NULL,response_body jsonb)");
                jdbc.execute("CREATE TABLE commands(key text PRIMARY KEY,payload text NOT NULL)");
                var store = new DbIdempotencyStore(pool, new ObjectMapper());
                var directory = Files.createTempDirectory("idempotency-http-");
                var tomcat = new Tomcat();
                tomcat.setBaseDir(directory.toString());
                tomcat.setPort(0);
                tomcat.getConnector();
                var context = tomcat.addContext("", directory.toString());
                Tomcat.addServlet(context, "commands", new HttpServlet() {
                    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                        String payload = new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        try (var connection = pool.getConnection()) {
                            connection.createStatement().execute("SET lock_timeout='100ms'");
                            try (var statement = connection.prepareStatement("INSERT INTO commands(key,payload) VALUES (?,?) ON CONFLICT (key) DO NOTHING")) {
                                statement.setString(1, request.getHeader("Idempotency-Key"));
                                statement.setString(2, payload);
                                statement.executeUpdate();
                            }
                            response.setStatus(201);
                            response.getWriter().write(payload);
                        } catch (java.sql.SQLException error) {
                            response.setStatus(503);
                            response.getWriter().write(error.getSQLState());
                        }
                    }
                });
                context.addServletMappingDecoded("/api/v1/commands", "commands");
                var definition = new FilterDef();
                definition.setFilterName("idempotency");
                definition.setFilter(new IdempotencyFilter(store, new CanonicalErrorWriter(new ObjectMapper())));
                context.addFilterDef(definition);
                var mapping = new FilterMap();
                mapping.setFilterName("idempotency");
                mapping.addURLPattern("/*");
                context.addFilterMap(mapping);
                tomcat.start();
                try (var client = HttpClient.newHttpClient(); var blocker = pool.getConnection()) {
                    var uri = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/v1/commands");
                    String key = UuidV7.generate();
                    var request = HttpRequest.newBuilder(uri).header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString("request")).build();
                    blocker.setAutoCommit(false);
                    blocker.createStatement().execute("LOCK TABLE commands IN ACCESS EXCLUSIVE MODE");
                    var failed = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(503, failed.statusCode());
                    assertEquals("55P03", failed.body());
                    assertTrue(store.find(key).isEmpty());
                    blocker.rollback();
                    assertEquals(201, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(201, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commands", Integer.class));
                    String cachedKey = UuidV7.generate();
                    store.saveIfAbsent(cachedKey, new IdempotencyStore.StoredResponse(store.find(key).orElseThrow().fingerprint(), 500, null, Map.of(), new byte[0]));
                    var cachedRequest = HttpRequest.newBuilder(uri).header("Idempotency-Key", cachedKey).POST(HttpRequest.BodyPublishers.ofString("request")).build();
                    assertEquals(201, client.send(cachedRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(201, store.find(cachedKey).orElseThrow().status());
                    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commands", Integer.class));
                    var conflict = HttpRequest.newBuilder(uri).header("Idempotency-Key", cachedKey).POST(HttpRequest.BodyPublishers.ofString("changed")).build();
                    assertEquals(422, client.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
                } finally {
                    tomcat.stop();
                    tomcat.destroy();
                }
            } finally {
                adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
