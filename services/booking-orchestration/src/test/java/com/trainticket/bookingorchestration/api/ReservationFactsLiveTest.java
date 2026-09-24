package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.*;
import com.trainticket.bookingorchestration.infrastructure.persistence.*;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_RESERVATION_FACTS_SOURCE", matches=".+")
class ReservationFactsLiveTest {
    @Test void actualCommercialFactsArePersistedAndConcurrentReservationsReuseOneBooking() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var source = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_RESERVATION_FACTS_SOURCE"))));
        var resolver = new ReservationFactsResolver(RestClient.builder(), mapper, System.getenv("TEST_LIVE_BASE_URL_TEMPLATE"));
        String orderId = source.path("order").path("orderId").asText();
        String traveler = source.path("order").path("travelerRefs").get(0).asText();
        String segment = source.path("order").path("segmentRefs").get(0).asText();
        String correlation = "corr-" + UuidV7.generate();
        var facts = resolver.resolve(orderId, segment, traveler, correlation);
        assertEquals(source.path("fare").path("breakdown").path("seatClassMultiplier").path("seatClass").asText(), facts.classRef());
        var leg = source.path("itinerary").path("legs").get(0);
        assertEquals(leg.path("servicePlanRef").asText(), facts.scheduledServiceRef());
        assertEquals(leg.path("departureTime").asText().substring(0,10), facts.serviceDate());
        String wrongTraveler = source.path("order").path("accountId").asText();
        assertThrows(BookingOrchestrationService.PreconditionFailedException.class, () -> resolver.resolve(orderId, segment, wrongTraveler, correlation));
        String schema = "reservation_facts_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET search_path TO " + schema);
        try (var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/005_booking_saga_step_deadlines.sql")));
                var saga = source.path("saga");
                jdbc.update("INSERT INTO booking_saga_snapshots(id,version,data) VALUES(?,?,?::jsonb)", saga.path("id").asText(), saga.path("version").asLong(), saga.path("data").toString());
                var service = new BookingOrchestrationService(Clock.systemUTC(),new TransactionalOutboxEventPublisher(new OutboxAppender(pool,mapper),mapper),
                    new PostgresBookingSagaRepository(pool,mapper),new PostgresSegmentBookingRepository(pool,mapper),
                    new PostgresPaymentIntentSagaReferenceRepository(pool),new PostgresConsumedEventRepository(pool),
                    new PostgresBookingSagaStepDeadlineRepository(pool));
                var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
                var results = new java.util.ArrayList<java.util.concurrent.Future<BookingOrchestrationService.RequestReservationResult>>();
                try (var executor = Executors.newFixedThreadPool(4)) {
                    for (int i=0;i<4;i++) results.add(executor.submit(() -> tx.execute(status -> service.requestReservation(saga.path("id").asText(),
                        new BookingOrchestrationService.RequestReservationCommand(segment,traveler,"sb-"+UuidV7.generate()),facts,correlation))));
                }
                String booking = results.getFirst().get().segmentBookingId();
                for (var result : results) assertEquals(booking,result.get().segmentBookingId());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM segment_booking_snapshots",Integer.class));
                assertEquals(facts.classRef(),jdbc.queryForObject("SELECT data->>'classRef' FROM segment_booking_snapshots",String.class));
                assertEquals(facts.offerItemRef(),jdbc.queryForObject("SELECT data->>'offerItemRef' FROM segment_booking_snapshots",String.class));
                var reloaded = new PostgresSegmentBookingRepository(pool,mapper).findById(booking).orElseThrow().booking();
                assertEquals(facts.scheduledServiceRef(),reloaded.scheduledServiceRef());
                assertEquals(facts.serviceDate(),reloaded.serviceDate());
                assertThrows(IllegalStateException.class, () -> reloaded.withSupplyReference(facts.scheduledServiceRef(), java.time.LocalDate.parse(facts.serviceDate()).plusDays(1).toString()));
                var events = jdbc.queryForList("SELECT envelope::text FROM outbox WHERE envelope->>'eventType'='SegmentReservationRequested'",String.class);
                assertEquals(1,events.size());
                var payload = mapper.readTree(events.getFirst()).path("payload");
                assertEquals(facts.classRef(),payload.path("classRef").asText());
                assertEquals(facts.scheduledServiceRef(),payload.path("scheduledServiceRef").asText());
                assertEquals(facts.serviceDate(),payload.path("serviceDate").asText());
                assertEquals(booking,payload.path("segmentBookingId").asText());
            } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
