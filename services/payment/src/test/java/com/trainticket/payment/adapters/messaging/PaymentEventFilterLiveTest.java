package com.trainticket.payment.adapters.messaging;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.Application;
import com.trainticket.payment.application.HandlerResult;
import com.trainticket.payment.application.PaymentInboundEventHandler;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentEventFilterLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_PAYMENT_FILTER_EVENTS", matches=".+")
    void ignoredEventsAvoidTransactionsWhileRequiredEventsRetainDeduplication() throws Exception {
        String schema = "payment_filter_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var mapper = context.getBean(ObjectMapper.class);
                var events = new ArrayList<EventEnvelope>();
                for (var row : mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_FILTER_EVENTS"))))) {
                    events.add(mapper.treeToValue(row, EventEnvelope.class));
                }
                var ignored = events.stream().filter(event -> !PaymentEventFilter.handles(event)).limit(100).toList();
                var changes=new ArrayList<EventEnvelope>();
                for(var row:mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_CHANGE_EVENTS"))))) {
                    var event=mapper.readValue(row.get(1).get(1).asText(),EventEnvelope.class);
                    if(event.eventType().equals("PostSalesApproved") && mapper.valueToTree(event.payload()).path("approvedActions").path("decisionKind").asText().equals("CHANGE"))changes.add(event);
                }
                assertFalse(changes.isEmpty());
                for(var change:changes)assertFalse(PaymentEventFilter.handles(change));
                assertEquals(100, ignored.size());
                var required = events.stream().filter(event -> "SegmentReservationRequested".equals(event.eventType()) && PaymentEventFilter.handles(event)).findFirst().orElseThrow();
                var handler = new PaymentEventFilter(context.getBean(PaymentInboundEventHandler.class));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                try (var blocker = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL")); var workers = Executors.newFixedThreadPool(2)) {
                    blocker.setAutoCommit(false);
                    blocker.createStatement().execute("LOCK TABLE " + schema + ".processed_events IN ACCESS EXCLUSIVE MODE");
                    try {
                        var ignoredResult = workers.submit(() -> {
                            for (var event : ignored) assertEquals(HandlerResult.SUCCESS, handler.handle(event));
                            for (var event : changes) assertEquals(HandlerResult.SUCCESS, handler.handle(event));
                        });
                        ignoredResult.get(2, TimeUnit.SECONDS);
                        var requiredResult = workers.submit(() -> handler.handle(required));
                        assertThrows(TimeoutException.class, () -> requiredResult.get(100, TimeUnit.MILLISECONDS));
                        blocker.rollback();
                        assertEquals(HandlerResult.SUCCESS, requiredResult.get(10, TimeUnit.SECONDS));
                    } finally { blocker.rollback(); }
                }
                assertEquals(HandlerResult.SUCCESS, handler.handle(required));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_payment_requests", Integer.class));
                assertEquals(required.eventId(), jdbc.queryForObject("SELECT event_id FROM processed_events", String.class));
                assertEquals(mapper.valueToTree(required.payload()).path("segmentBookingId").asText(), jdbc.queryForObject("SELECT segment_booking_id FROM reservation_payment_requests", String.class));
                EventEnvelope approved=null;
                for(String line:Files.readAllLines(Path.of(System.getenv("TEST_MISSING_REFUND_EVENTS")))) {
                    var row=mapper.readTree(line);var fields=row.path("fields");
                    for(int index=0;index<fields.size();index+=2)if(fields.get(index).asText().equals("envelope")) {
                        var event=mapper.readValue(fields.get(index+1).asText(),EventEnvelope.class);
                        if(event.eventType().equals("PostSalesApproved"))approved=event;
                    }
                }
                assertNotNull(approved);
                assertEquals(HandlerResult.TRANSIENT_FAILURE,handler.handle(approved));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Integer.class,approved.eventId()));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM refund_snapshots",Integer.class));
            } finally {
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
