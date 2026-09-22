package com.trainticket.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class RefundCaseLookupTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
    void exactCaseLookupAndConcurrentUpdatesUseOneDatabaseConnection() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        String schema = "refund_lookup_" + UUID.randomUUID().toString().replace("-", "");
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_payment_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/007_refund_case_lookup.sql")));
                ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                var repository = new PostgresRefundRepository(dataSource, mapper);
                Instant now = Instant.now();
                var payments = new PostgresPaymentIntentRepository(dataSource, mapper);
                var memoryPayments = new InMemoryPaymentIntentRepository();
                for (int index = 0; index < 20; index++) {
                    Instant createdAt = now.plusSeconds(index);
                    var payment = PaymentIntent.create("ord-payment-latest", "purchase", Money.fromMinorUnits(100, "CNY"),
                        "acct-refund-lookup", createdAt.plusSeconds(900), "payment-latest-" + index, createdAt, "cmd-create", "corr-create");
                    payments.save(payment);
                    memoryPayments.save(payment);
                    assertEquals(payment.paymentIntentId(), payments.findLatestByBusinessRef("ord-payment-latest").orElseThrow().paymentIntentId());
                    assertEquals(payment.paymentIntentId(), memoryPayments.findLatestByBusinessRef("ord-payment-latest").orElseThrow().paymentIntentId());
                }
                var intent = PaymentIntent.create("ord-refund-lookup", "purchase", Money.fromMinorUnits(35000, "CNY"),
                    "acct-refund-lookup", now.plusSeconds(900), "create-refund-lookup", now, "cmd-create", "corr-create");
                intent.capture(intent.amount(), "channel", "txn-lookup", now, "cmd-capture", "cmd-capture", "corr-capture");
                payments.save(intent);
                var captured = payments.findById(intent.paymentIntentId()).orElseThrow();
                var originalEvent = intent.domainEvents().stream().filter(com.trainticket.payment.domain.PaymentCaptured.class::isInstance).findFirst().orElseThrow();
                var persistedEvent = captured.domainEvents().stream().filter(com.trainticket.payment.domain.PaymentCaptured.class::isInstance).findFirst().orElseThrow();
                assertEquals(originalEvent.envelope().eventId(), persistedEvent.envelope().eventId());
                assertEquals(originalEvent.envelope().occurredAt(), persistedEvent.envelope().occurredAt());
                var capturePayload = (java.util.Map<?, ?>) com.trainticket.payment.application.EventEnvelopeMapper.fromDomainEvent(persistedEvent).payload();
                assertEquals(intent.paymentIntentId(), capturePayload.get("paymentIntentId"));
                assertEquals(intent.businessRef(), capturePayload.get("businessRef"));
                assertEquals(35000L, ((java.util.Map<?, ?>) capturePayload.get("capturedAmount")).get("minorUnits"));
                var first = Refund.request(intent, Money.fromMinorUnits(10000, "CNY"), "case-lookup", "ticket refund",
                    "refund-lookup-1", now, "cmd-refund", "corr-refund");
                var second = Refund.request(intent, Money.fromMinorUnits(2000, "CNY"), "case-lookup", "ticket refund",
                    "refund-lookup-2", now, "cmd-refund", "corr-refund");
                var unrelated = Refund.request(intent, Money.fromMinorUnits(1000, "CNY"), "case-lookup-extra", "ticket refund",
                    "refund-lookup-3", now, "cmd-refund", "corr-refund");
                for (Refund refund : List.of(first, second, unrelated)) {
                    repository.save(refund);
                }
                var writer = repository.findById(first.refundId()).orElseThrow();
                var stale = repository.findById(first.refundId()).orElseThrow();
                writer.submitToChannel("refund-txn-lookup");
                repository.save(writer);
                stale.fail("CHANNEL_DOWN", true, now, "cmd-fail", "cmd-fail", "corr-fail");
                assertThrows(OptimisticConcurrencyException.class, () -> repository.save(stale));
                writer.settle(intent, "refund-txn-lookup", now, "cmd-settle", "cmd-settle", "corr-settle");
                repository.save(writer);
                var reloaded = new PostgresRefundRepository(dataSource, mapper);
                var expected = List.of(first.refundId(), second.refundId()).stream().sorted().toList();
                for (int attempt = 0; attempt < 10; attempt++) {
                    var found = reloaded.findByBusinessCaseRef("case-lookup");
                    assertEquals(expected, found.stream().map(Refund::refundId).toList());
                    assertEquals(12000, found.stream().mapToLong(refund -> refund.amount().toMinorUnits()).sum());
                    assertEquals(RefundStatus.SETTLED, reloaded.findById(first.refundId()).orElseThrow().status());
                    assertTrue(found.stream().allMatch(refund -> refund.paymentIntentId().equals(intent.paymentIntentId())));
                    assertTrue(reloaded.findByBusinessCaseRef("case").isEmpty());
                }
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
