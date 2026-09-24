package com.trainticket.bookingorchestration.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ChangeReservationLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CHANGE_CASE",matches=".+")
    void concurrentChangeReservationsKeepOneBookingAndItsSelectedClass() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_CASE"))));
        var offer=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_OFFER"))));
        String schema="change_booking_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig(); config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setConnectionInitSql("SET search_path TO "+schema); config.setMaximumPoolSize(8);
        try(var admin=java.sql.DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var pool=new HikariDataSource(config); var workers=Executors.newFixedThreadPool(8)) {
                var jdbc=new JdbcTemplate(pool);
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/005_booking_saga_step_deadlines.sql")));
                var sagas=new PostgresBookingSagaRepository(pool,mapper);
                var bookings=new PostgresSegmentBookingRepository(pool,mapper);
                var service=new BookingOrchestrationService(Clock.systemUTC(),new TransactionalOutboxEventPublisher(new OutboxAppender(pool,mapper),mapper),
                    sagas,bookings,new PostgresPaymentIntentSagaReferenceRepository(pool),new PostgresConsumedEventRepository(pool),
                    new PostgresBookingSagaStepDeadlineRepository(pool));
                var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
                String caseId=source.path("caseId").asText(),order=source.path("journeyOrderId").asText();
                String item=offer.path("items").get(0).path("offerItemId").asText(),segment=offer.path("items").get(0).path("segmentRef").asText();
                String traveler=source.path("scope").path("travelerRefs").get(0).asText();
                var itinerary=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_ITINERARY"))));
                String scheduledService=itinerary.path("legs").get(0).path("servicePlanRef").asText();
                String serviceDate=itinerary.path("legs").get(0).path("departureTime").asText().substring(0,10);
                var calls=new ArrayList<java.util.concurrent.Future<BookingOrchestrationService.SegmentBookingDetail>>();
                for(int index=0;index<16;index++)calls.add(workers.submit(()->tx.execute(status->service.requestChangeReservation(caseId,order,item,segment,traveler,"FIRST_CLASS",scheduledService,serviceDate,"corr-"+com.trainticket.platformkit.idempotency.UuidV7.generate()))));
                var first=calls.getFirst().get(10,TimeUnit.SECONDS);
                for(var call:calls)assertEquals(first.segmentBookingId(),call.get(10,TimeUnit.SECONDS).segmentBookingId());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM segment_booking_snapshots",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='SegmentReservationRequested'",Integer.class));
                assertEquals("FIRST_CLASS",jdbc.queryForObject("SELECT envelope->'payload'->>'classRef' FROM outbox WHERE envelope->>'eventType'='SegmentReservationRequested'",String.class));
                assertEquals("FIRST_CLASS",bookings.findById(first.segmentBookingId()).orElseThrow().booking().classRef());
                assertTrue(sagas.findByJourneyOrderId(order).isEmpty());
                assertThrows(BookingOrchestrationService.PreconditionFailedException.class,()->tx.execute(status->service.requestChangeReservation(caseId,order,item,segment,traveler,"SECOND_CLASS",scheduledService,serviceDate,"corr-test")));
                for(int index=0;index<16;index++)calls.add(workers.submit(()->tx.execute(status->service.cancelUnpaidChangeReservation(caseId,order,first.segmentBookingId(),"corr-"+com.trainticket.platformkit.idempotency.UuidV7.generate()))));
                for(int index=16;index<calls.size();index++)assertEquals("CANCELLED",calls.get(index).get(10,TimeUnit.SECONDS).status());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='SegmentBookingCancelled'",Integer.class));
                assertThrows(BookingOrchestrationService.PreconditionFailedException.class,()->tx.execute(status->service.cancelUnpaidChangeReservation("psc-other",order,first.segmentBookingId(),"corr-test")));
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
