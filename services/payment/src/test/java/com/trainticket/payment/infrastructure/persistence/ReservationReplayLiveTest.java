package com.trainticket.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.application.ReservationPaymentRequest;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationReplayLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_PAYMENT_FILTER_EVENTS", matches=".+")
    void concurrentReservationReplayKeepsTransactionUsable() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventEnvelope event = null;
        for (var row : mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_FILTER_EVENTS"))))) {
            if ("SegmentReservationRequested".equals(row.path("eventType").asText())) { event = mapper.treeToValue(row, EventEnvelope.class); break; }
        }
        assertNotNull(event);
        var payload = mapper.valueToTree(event.payload());
        var request = new ReservationPaymentRequest(event.eventId(), payload.path("segmentBookingId").asText(), payload.path("journeyOrderId").asText(),
            payload.path("segmentRef").asText(), payload.path("travelerRef").asText(), payload.path("idempotencyKey").asText(), event.correlationId(), event.occurredAt());
        String schema = "payment_reservation_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            var config = new HikariConfig();
            config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
            config.setConnectionInitSql("SET search_path TO " + schema);
            config.setMaximumPoolSize(8);
            try (var pool = new HikariDataSource(config); var workers = Executors.newFixedThreadPool(8)) {
                var jdbc = new JdbcTemplate(pool);
                jdbc.execute(Files.readString(Path.of("migrations/001_payment_persistence.sql")));
                var repository = new PostgresReservationPaymentRequestRepository(pool, mapper);
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
                var futures = new ArrayList<java.util.concurrent.Future<ReservationPaymentRequest>>();
                for (int index = 0; index < 16; index++) {
                    futures.add(workers.submit(() -> transaction.execute(status -> {
                        var saved = repository.saveIfAbsent(request);
                        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
                        return saved;
                    })));
                }
                for (var future : futures) assertEquals(request, future.get(10, TimeUnit.SECONDS));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_payment_requests", Integer.class));
            } finally { admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
