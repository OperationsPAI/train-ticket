package com.trainticket.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.application.EventEnvelopeMapper;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentCaptured;
import com.trainticket.payment.domain.Money;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class CaptureIdentityTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CAPTURED_PAYMENT",matches=".+")
    void capturedFactsPreservePurposeAndPayerAcrossStorage() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var source = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPTURED_PAYMENT"))));
        var created = source.path("domainEvents").get(0);
        var captured = java.util.stream.StreamSupport.stream(source.path("domainEvents").spliterator(),false)
            .filter(event -> event.path("eventType").asText().equals("PaymentCaptured")).findFirst().orElseThrow();
        var payload = captured.path("payload");
        var intent = PaymentIntent.create(source.path("businessRef").asText(),source.path("purpose").asText(),
            Money.fromMinorUnits(source.path("amount").path("minorUnits").asLong(),source.path("amount").path("currency").asText()),
            source.path("payerRef").asText(),Instant.parse(source.path("expiresAt").asText()),source.path("idempotencyKey").asText(),
            Instant.parse(created.path("occurredAt").asText()),created.path("causationId").asText(),created.path("correlationId").asText());
        intent.capture(Money.fromMinorUnits(payload.path("capturedAmount").path("minorUnits").asLong(),payload.path("capturedAmount").path("currency").asText()),
            payload.path("channel").asText(),payload.path("channelTransactionId").asText(),Instant.parse(captured.path("occurredAt").asText()),
            captured.path("causationId").asText(),captured.path("causationId").asText(),captured.path("correlationId").asText());
        var config = new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);
        try(var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            String schema="capture_identity_"+UUID.randomUUID().toString().replace("-","");
            jdbc.execute("CREATE SCHEMA "+schema);
            jdbc.execute("SET search_path TO "+schema);
            try {
                jdbc.execute(Files.readString(Path.of("migrations/001_payment_persistence.sql")));
                var repository=new PostgresPaymentIntentRepository(pool,mapper);
                repository.save(intent);
                var restored=repository.findById(intent.paymentIntentId()).orElseThrow();
                var fact=(PaymentCaptured)restored.domainEvents().stream().filter(PaymentCaptured.class::isInstance).findFirst().orElseThrow();
                assertEquals(source.path("payerRef").asText(),fact.payerRef());
                assertEquals(source.path("purpose").asText(),fact.purpose());
                var wire=mapper.valueToTree(EventEnvelopeMapper.fromDomainEvent(fact)).path("payload");
                assertEquals(source.path("businessRef").asText(),wire.path("businessRef").asText());
                assertEquals(source.path("payerRef").asText(),wire.path("payerRef").asText());
                assertEquals(fact.purpose(),wire.path("purpose").asText());
                assertEquals(intent.capturedAmount(),restored.capturedAmount());
            } finally {jdbc.execute("SET search_path TO public");jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
