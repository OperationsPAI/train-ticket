package com.trainticket.bookingorchestration.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStep;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class SingleConnectionLookupTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL", matches=".+")
    void sagaAndSegmentQueriesCompleteWithOneConnection() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        String schema = "booking_lookup_" + UUID.randomUUID().toString().replace("-", "");
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                var sagas = new PostgresBookingSagaRepository(dataSource, mapper);
                var bookings = new PostgresSegmentBookingRepository(dataSource, mapper);
                var service = new com.trainticket.bookingorchestration.application.BookingOrchestrationService(
                    Clock.systemUTC(), new TransactionalOutboxEventPublisher(
                        new com.trainticket.platformkit.persistence.OutboxAppender(dataSource, mapper), mapper),
                    sagas, bookings, new PostgresPaymentIntentSagaReferenceRepository(dataSource), new PostgresConsumedEventRepository(dataSource));
                service.handleUpstreamEvent(new com.trainticket.platformkit.messaging.EventEnvelope(
                    com.trainticket.platformkit.messaging.PrefixedIds.newEventId(), "ChannelOrderSubmitted", java.time.Instant.now(),
                    com.trainticket.platformkit.messaging.PrefixedIds.newCorrelationId(), com.trainticket.platformkit.messaging.PrefixedIds.newCommandId(),
                    "payment-channel", 1, java.util.Map.of()));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                var saga = BookingSaga.start("saga-lookup", "ord-lookup", "1", "purchase",
                    List.of(new BookingSagaStep("RISK", "risk-lookup", Duration.ofMinutes(1), 1, "cancel")), Clock.systemUTC());
                sagas.save(saga, "corr-lookup");
                var booking = SegmentBooking.requestReservation("sb-lookup", "ord-lookup", "offer-item", "seg-lookup", "tvl-lookup", "purchase", Clock.systemUTC());
                bookings.save(booking, saga.sagaId());
                for (int attempt = 0; attempt < 10; attempt++) {
                    assertEquals(saga.sagaId(), sagas.findByJourneyOrderId("ord-lookup").orElseThrow().sagaId());
                    assertEquals(saga.sagaId(), sagas.findByCorrelationId("corr-lookup").orElseThrow().sagaId());
                    assertEquals(booking.segmentBookingId(), bookings.findBySagaId(saga.sagaId()).getFirst().booking().segmentBookingId());
                    assertEquals(booking.segmentBookingId(), bookings.findByIdempotencyKey(booking.idempotencyKey()).orElseThrow().booking().segmentBookingId());
                }
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
