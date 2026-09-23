package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.Application;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.EventSubscriber;
import com.trainticket.bookingorchestration.application.SubscriberConfig;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_BOOKING_EVENTS", matches = ".+")
class SubscriberFilterLiveTest {
    @Test
    void acknowledgesIrrelevantEventsWithoutDatabaseConnectionsAndHandlesOrderCreation() throws Exception {
        String schema = "booking_filter_" + UUID.randomUUID().toString().replace("-", "");
        String stream = "events:" + schema;
        String group = "filter-test";
        var redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"));
             var connection = redis.connect()) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var app = SpringApplication.run(Application.class, "--server.port=0",
                "--booking-orchestration.messaging.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema=" + schema,
                "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"))) {
                var mapper = app.getBean(ObjectMapper.class);
                var recorded = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_BOOKING_EVENTS"))));
                EventEnvelope created = null;
                EventEnvelope confirmed = null;
                for (var entry : recorded) {
                    var envelope = mapper.readValue(entry.get(1).get(1).asText(), EventEnvelope.class);
                    if (envelope.eventType().equals("JourneyOrderCreated")) created = envelope;
                    if (envelope.eventType().equals("JourneyOrderConfirmed")) confirmed = envelope;
                }
                assertNotNull(created);
                assertNotNull(confirmed);
                var operations = new LettuceRedisStreamOperations(connection);
                operations.createGroup(stream, group);
                var dataSource = (HikariDataSource) app.getBean(DataSource.class);
                var held = new ArrayList<Connection>();
                try {
                    for (int index = 0; index < dataSource.getMaximumPoolSize(); index++) held.add(dataSource.getConnection());
                    operations.publish(stream, mapper.writeValueAsString(confirmed));
                    app.getBean(EventSubscriber.class).subscribe(new SubscriberConfig(List.of(stream), group, "filter-consumer",
                        app.getBean(BookingOrchestrationService.class)::handleUpstreamEvent));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean acknowledged = false;
                    while (System.nanoTime() < deadline) {
                        var info = (List<?>) connection.sync().xinfoGroups(stream).getFirst();
                        int entriesRead = info.indexOf("entries-read");
                        if (entriesRead >= 0 && Long.valueOf(1).equals(info.get(entriesRead + 1))
                            && connection.sync().xpending(stream, group).getCount() == 0) {
                            acknowledged = true;
                            break;
                        }
                        Thread.sleep(10);
                    }
                    assertTrue(acknowledged, "irrelevant event must finish while all database connections are held");
                } finally {
                    for (var heldConnection : held) heldConnection.close();
                }
                operations.publish(stream, mapper.writeValueAsString(created));
                var jdbc = new JdbcTemplate(dataSource);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int sagas = 0;
                while (System.nanoTime() < deadline) {
                    sagas = jdbc.queryForObject("SELECT count(*) FROM booking_saga_snapshots", Integer.class);
                    if (sagas == 1) break;
                    Thread.sleep(20);
                }
                assertEquals(1, sagas, "order creation must still create its real booking saga");
            } finally {
                connection.sync().del(stream);
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally {
            redis.shutdown();
        }
    }
}
