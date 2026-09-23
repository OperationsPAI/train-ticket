package com.trainticket.bookingorchestration.api;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.Application;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.ChangeCompensationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_COMPENSATION_SOURCE",matches=".+")
class CapacityCompensationLiveTest {
    @Test
    void recordedConfirmedReplacementCancelsExactlyOnceAfterActualRejection() throws Exception {
        String schema="booking_compensation_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--booking-orchestration.messaging.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379")) {
                var mapper=app.getBean(ObjectMapper.class);
                var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_COMPENSATION_SOURCE"))));
                var row=source.path("booking_orchestration");String booking=row.path("id").asText();
                String caseId="psc-"+source.path("post_sales").path("id").asText();
                var change=(com.fasterxml.jackson.databind.node.ObjectNode)source.path("post_sales").path("data").deepCopy();
                change.put("caseId",caseId);change.put("status","APPROVED");
                var service=app.getBean(ChangeCompensationService.class);
                var jdbc=new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO segment_booking_snapshots(id,version,data) VALUES(?,?,?::jsonb)",booking,row.path("version").asLong(),row.path("data").toString());
                assertThrows(BookingOrchestrationService.PreconditionFailedException.class,()->service.cancel(booking,caseId,change,"corr-test"));
                var target=change.path("changeOperations").path("target");var leg=target.path("itinerary").path("legs").get(0);
                var hold=change.path("changeOperations").path("confirmed:"+booking).path("hold");
                var command=mapper.createObjectNode();
                for(String field:new String[]{"segmentBookingId","journeyOrderId","travelerRef","segmentRef","capacityHoldId","classRef"})command.set(field,row.path("data").path(field));
                command.set("capacityUnitRef",hold.path("capacityUnitRef"));command.set("interval",hold.path("interval"));
                command.set("scheduledServiceRef",leg.path("servicePlanRef"));command.put("serviceDate",leg.path("departureTime").asText().substring(0,10));
                command.put("issuePurpose","REPLACEMENT");command.set("expiresAt",leg.path("departureTime"));
                try(var client=HttpClient.newHttpClient()) {
                    var response=client.send(HttpRequest.newBuilder(URI.create(System.getenv("TEST_SEAT_ASSIGNMENT_URL")+"/api/v1/internal/seat-allocations"))
                        .header("Content-Type","application/json").header("Idempotency-Key",com.trainticket.platformkit.idempotency.UuidV7.generate())
                        .POST(HttpRequest.BodyPublishers.ofString(command.toString())).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(422,response.statusCode(),response.body());
                    var rejection=mapper.readTree(response.body());assertEquals("DOMAIN_RULE_VIOLATION",rejection.path("code").asText());
                    ((com.fasterxml.jackson.databind.node.ObjectNode)change.path("changeOperations")).set("capacity-rejected",mapper.valueToTree(Map.of("segmentBookingId",booking,"code",rejection.path("code").asText(),"message",rejection.path("message").asText())));
                }
                try(var workers=Executors.newFixedThreadPool(4)) {
                    var calls=new java.util.ArrayList<java.util.concurrent.Future<BookingOrchestrationService.SegmentBookingDetail>>();
                    for(int index=0;index<8;index++)calls.add(workers.submit(()->service.cancel(booking,caseId,change,"corr-"+com.trainticket.platformkit.idempotency.UuidV7.generate())));
                    for(var call:calls)assertEquals("CANCELLED",call.get(10,TimeUnit.SECONDS).status());
                }
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='SegmentBookingCancelled'",Integer.class));
                assertEquals("CHANGE_CAPACITY_UNAVAILABLE",jdbc.queryForObject("SELECT data->>'cancellationReason' FROM segment_booking_snapshots WHERE id=?",String.class,booking));
                assertThrows(BookingOrchestrationService.PreconditionFailedException.class,()->service.cancel(booking,caseId+"invalid",change,"corr-test"));
            } finally { admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
}
