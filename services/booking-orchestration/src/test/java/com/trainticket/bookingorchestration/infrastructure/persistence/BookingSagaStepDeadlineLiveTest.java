package com.trainticket.bookingorchestration.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.BookingSagaStepDeadlineRepository;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.application.ReservationFactsResolver;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BookingSagaStepDeadlineLiveTest {
    private static final Instant START = Instant.parse("2026-09-24T02:00:00Z");

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
    void dueWaitsAreServedInDeadlineOrderAndClearOnCompletion() throws Exception {
        withSchema(pool -> {
            var jdbc = new JdbcTemplate(pool);
            var deadlines = new PostgresBookingSagaStepDeadlineRepository(pool);
            deadlines.schedule("saga-later", "saga-later:reservations", START.plusSeconds(50));
            deadlines.schedule("saga-earlier", "saga-earlier:risk", START.plusSeconds(10));
            deadlines.schedule("saga-future", "saga-future:risk", START.plusSeconds(3600));
            deadlines.schedule("saga-earlier", "saga-earlier:risk", START.plusSeconds(20));

            List<BookingSagaStepDeadlineRepository.BookingSagaStepDeadline> due =
                deadlines.findDue(START.plusSeconds(60), 10);
            assertEquals(List.of("saga-earlier", "saga-later"),
                due.stream().map(BookingSagaStepDeadlineRepository.BookingSagaStepDeadline::sagaId).toList(),
                "the oldest wait is served first and a rescheduled row keeps one row per step");
            assertEquals(START.plusSeconds(20), due.getFirst().deadline());
            assertEquals(List.of("saga-earlier"), deadlines.findDue(START.plusSeconds(60), 1).stream()
                .map(BookingSagaStepDeadlineRepository.BookingSagaStepDeadline::sagaId).toList());

            deadlines.clear("saga-earlier", "saga-earlier:risk");
            assertEquals(List.of("saga-later"), deadlines.findDue(START.plusSeconds(60), 10).stream()
                .map(BookingSagaStepDeadlineRepository.BookingSagaStepDeadline::sagaId).toList());
            deadlines.clearSaga("saga-later");
            assertEquals(List.of(), deadlines.findDue(START.plusSeconds(3599), 10),
                "a wait that is not due yet stays out of the sweep batch");
            assertEquals(List.of("saga-future"), deadlines.findDue(START.plusSeconds(3600), 10).stream()
                .map(BookingSagaStepDeadlineRepository.BookingSagaStepDeadline::sagaId).toList(),
                "the wait becomes due on its own deadline");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM booking_saga_step_deadlines", Integer.class),
                "the wait of the saga that is still reserving stays indexed");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
    void overdueReservationStepIsFailedAndCompensatedThroughTheSweep() throws Exception {
        withSchema(pool -> {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            var jdbc = new JdbcTemplate(pool);
            var sagas = new PostgresBookingSagaRepository(pool, mapper);
            var bookings = new PostgresSegmentBookingRepository(pool, mapper);
            var deadlines = new PostgresBookingSagaStepDeadlineRepository(pool);
            var publisher = new TransactionalOutboxEventPublisher(
                new OutboxAppender(pool, mapper), mapper);
            var service = new BookingOrchestrationService(Clock.systemUTC(), publisher, sagas, bookings,
                new PostgresPaymentIntentSagaReferenceRepository(pool),
                new PostgresConsumedEventRepository(pool), deadlines);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
            String order = "ord-live-" + UUID.randomUUID();
            String segment = "seg-live-" + UUID.randomUUID();
            String traveler = "tvl-live-" + UUID.randomUUID();
            String correlation = PrefixedIds.newCorrelationId();

            String sagaId = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
                order, "acc-live", "ofi-live", List.of(traveler), List.of(segment)), "key-live", correlation).sagaId();
            assertInstanceOf(HandlerResult.Success.class, tx.execute(status -> service.handleUpstreamEvent(
                new EventEnvelope(PrefixedIds.newEventId(), "RiskAssessmentCompleted", Instant.now(), correlation,
                    PrefixedIds.newCommandId(), "risk-compliance", 1,
                    Map.of("sagaId", sagaId, "verdict", "PASS")))));
            var reservation = tx.execute(status -> service.requestReservation(sagaId,
                new BookingOrchestrationService.RequestReservationCommand(segment, traveler,
                    "sb-live-" + PrefixedIds.newEventId()),
                new ReservationFactsResolver.Facts(order, segment, traveler, "ofi-live", "SECOND_CLASS",
                    "ss-live-1", "2026-10-24", false), correlation));

            // The wait of this reservation began long before the sweep ran, so both the recorded
            // wait start and the wait index carry a past deadline.
            String stepKey = sagaId + ":" + segment;
            String pastStart = Instant.now().minus(Duration.ofMinutes(10)).toString();
            var snapshot = mapper.readTree((String) jdbc.queryForMap(
                "SELECT data::text AS data FROM booking_saga_snapshots WHERE id = ?", sagaId).get("data"));
            for (var step : snapshot.path("steps")) {
                if (step.path("name").asText().startsWith("reserve-")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) step).put("startedAt", pastStart);
                }
            }
            jdbc.update("UPDATE booking_saga_snapshots SET data = ?::jsonb WHERE id = ?", snapshot.toString(), sagaId);
            deadlines.schedule(sagaId, stepKey, Instant.parse(pastStart).plus(Duration.ofMinutes(5)));

            List<BookingSagaStepDeadlineRepository.BookingSagaStepDeadline> due = service.findDueStepDeadlines(10);
            assertEquals(List.of(stepKey),
                due.stream().map(BookingSagaStepDeadlineRepository.BookingSagaStepDeadline::stepIdempotencyKey).toList());

            Boolean swept = tx.execute(status -> service.expireTimedOutStep(sagaId, due.getFirst().stepIdempotencyKey()));
            assertTrue(swept);

            var saga = new PostgresBookingSagaRepository(pool, mapper).findById(sagaId).orElseThrow();
            assertEquals("FAILED", saga.status().name());
            assertEquals("SAGA_STEP_TIMED_OUT", saga.terminalReason().orElseThrow());
            var booking = bookings.findById(reservation.segmentBookingId()).orElseThrow().booking();
            assertEquals("CANCELLED", booking.status().name());
            assertEquals("SAGA_STEP_TIMED_OUT", booking.cancellationReason().orElseThrow());
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'SegmentBookingCancelled'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'BookingSagaFailed'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM booking_saga_step_deadlines", Integer.class));
        });
    }

    private static void withSchema(SchemaWork work) throws Exception {
        String schema = "booking_step_deadline_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setConnectionInitSql("SET search_path TO " + schema);
        config.setMaximumPoolSize(4);
        try (var admin = java.sql.DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (var pool = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(pool);
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/005_booking_saga_step_deadlines.sql")));
                work.run(pool);
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private interface SchemaWork {
        void run(HikariDataSource pool) throws Exception;
    }

}
