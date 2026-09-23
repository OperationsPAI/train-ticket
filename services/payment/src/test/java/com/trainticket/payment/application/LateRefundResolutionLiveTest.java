package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.DomainRuleViolation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class LateRefundResolutionLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_LATE_REFUND_PAYMENT", matches=".+")
    void lateCollectionRefundIsDurableWithoutRevivingPayment() throws Exception {
        var mapper = new ObjectMapper();
        var payment = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_LATE_REFUND_PAYMENT"))));
        var lateCase = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_LATE_REFUND_CASE"))));
        String schema = "late_refund_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                String id = payment.path("paymentIntentId").asText(), business = payment.path("businessRef").asText();
                jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES (?,1,?::jsonb)", id, payment.toString());
                jdbc.update("INSERT INTO late_payment_case_snapshots(id,version,data) VALUES (?,1,?::jsonb)", lateCase.path("latePaymentCaseId").asText(), lateCase.toString());
                var service = context.getBean(PaymentCommandService.class);
                assertThrows(DomainRuleViolation.class, () -> service.prepareLatePaymentRefund(id, id, "ancillary:" + business, "LATE_PAYMENT", UUID.randomUUID().toString(), "corr-test"));
                try (var workers = Executors.newFixedThreadPool(8)) {
                    var calls = new ArrayList<java.util.concurrent.Future<String>>();
                    for (int index = 0; index < 16; index++) {
                        calls.add(workers.submit(() -> service.prepareLatePaymentRefund(id, business, "ancillary:" + business,
                            "LATE_PAYMENT", UUID.randomUUID().toString(), "corr-test").refundId()));
                    }
                    String refund = calls.getFirst().get(10, TimeUnit.SECONDS);
                    for (var call : calls) assertEquals(refund, call.get(10, TimeUnit.SECONDS));
                    assertEquals(refund, service.latePaymentForIntent(id).refundId());
                }
                assertEquals("EXPIRED", service.getIntent(id).status().name());
                assertEquals(1200, service.getIntent(id).capturedAmount().toMinorUnits());
                assertEquals(0, service.getIntent(id).refundedAmount().toMinorUnits());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_snapshots", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RefundRequested'", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PaymentCaptured'", Integer.class));
                assertThrows(DomainRuleViolation.class, () -> service.prepareLatePaymentRefund(id, business, "another-case", "LATE_PAYMENT", UUID.randomUUID().toString(), "corr-test"));
                assertEquals("OPEN", service.latePaymentForIntent(id).status().name());
                if (System.getenv("TEST_LATE_REFUND_CHANNEL") != null) {
                    var settled = service.submitLatePaymentRefund(id, "corr-" + com.trainticket.platformkit.idempotency.UuidV7.generate());
                    assertEquals("SETTLED", settled.status().name());
                    assertEquals(settled.refundId(), service.submitLatePaymentRefund(id, "corr-replay").refundId());
                    assertEquals("EXPIRED", service.getIntent(id).status().name());
                    assertEquals(1200, service.getIntent(id).refundedAmount().toMinorUnits());
                    assertEquals("RESOLVED", service.latePaymentForIntent(id).status().name());
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='LatePaymentCaseResolved'", Integer.class));
                    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PaymentCaptured'", Integer.class));
                }
            } finally {
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
