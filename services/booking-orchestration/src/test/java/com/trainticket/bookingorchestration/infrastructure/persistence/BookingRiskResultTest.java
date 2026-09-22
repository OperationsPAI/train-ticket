package com.trainticket.bookingorchestration.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.HandlerResult;
import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BookingRiskResultTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_RISK_COMPLETION_DIRECTORY", matches=".+")
    void authoritativeRiskResultsControlBookingAndSurviveReplay() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "booking_risk_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = new HikariDataSource(config)) {
            var adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var dataSource = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                var sagas = new PostgresBookingSagaRepository(dataSource,mapper);
                var publisher = new TransactionalOutboxEventPublisher(new OutboxAppender(dataSource,mapper),mapper);
                var service = new BookingOrchestrationService(Clock.systemUTC(), publisher, sagas,
                    new PostgresSegmentBookingRepository(dataSource,mapper),
                    new PostgresPaymentIntentSagaReferenceRepository(dataSource),new PostgresConsumedEventRepository(dataSource));
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                for (String decision : List.of("ALLOW", "DENY")) {
                    var event = mapper.readValue(Files.readString(Path.of(System.getenv("TEST_RISK_COMPLETION_DIRECTORY"), "risk-completion-" + decision + ".json")),EventEnvelope.class);
                    var payload = mapper.valueToTree(event.payload());
                    String sagaId = payload.path("sagaId").asText();
                    String orderId = payload.path("journeyOrderId").asText();
                    var saga = BookingSaga.start(sagaId,orderId,"1","purchase",List.of(BookingSaga.stepPlan("risk-check",sagaId+":risk",Duration.ofMinutes(2),2,"none")),Clock.systemUTC());
                    transaction.executeWithoutResult(status -> sagas.save(saga,event.correlationId()));
                    assertInstanceOf(HandlerResult.Success.class,transaction.execute(status -> service.handleUpstreamEvent(event)));
                    var expected = decision.equals("ALLOW") ? BookingSagaStatus.RESERVING : BookingSagaStatus.FAILED;
                    var restarted = new PostgresBookingSagaRepository(dataSource,mapper);
                    var saved = restarted.findById(sagaId).orElseThrow();
                    assertEquals(expected,saved.status());
                    long version = saved.version();
                    assertInstanceOf(HandlerResult.Success.class,transaction.execute(status -> service.handleUpstreamEvent(event)));
                    assertEquals(version,restarted.findById(sagaId).orElseThrow().version());
                    assertEquals(decision.equals("DENY") ? 1 : 0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->'payload'->>'sagaId'=?",Integer.class,sagaId));
                    if (decision.equals("DENY")) assertEquals("risk-blocked",saved.terminalReason().orElseThrow());
                }
            } finally { adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
