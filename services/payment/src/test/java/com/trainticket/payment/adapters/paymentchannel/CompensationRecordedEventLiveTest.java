package com.trainticket.payment.adapters.paymentchannel;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.adapters.messaging.PaymentEventFilter;
import com.trainticket.payment.application.*;
import com.trainticket.payment.infrastructure.persistence.*;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompensationRecordedEventLiveTest {
    @Test
    void recordedApprovalAndAppliedPreserveCapturedPaymentAcrossReplay() throws Exception {
        verifyIgnored("/compensation-recorded.json", true);
    }

    @Test
    void recordedApprovalAndAppliedNeedNoPaymentSnapshot() throws Exception {
        verifyIgnored("/compensation-recorded.json", false);
    }

    @Test
    void recordedWalletCompensationPreservesOriginalPayment() throws Exception {
        verifyIgnored("/wallet-compensation-recorded.json", true);
    }

    private void verifyIgnored(String resource, boolean includePayment) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source = mapper.readTree(getClass().getResourceAsStream(resource));
        var payment = source.required("payment");
        var approved = mapper.treeToValue(source.required("approved"), EventEnvelope.class);
        var applied = mapper.treeToValue(source.required("applied"), EventEnvelope.class);
        var recordedCase = source.required("case");
        assertEquals("COMPENSATION", recordedCase.required("caseType").asText());
        assertEquals("APPLIED", recordedCase.required("status").asText());
        assertEquals("psc-" + recordedCase.required("caseId").asText(), source.required("applied").at("/payload/caseId").asText());
        assertEquals(recordedCase.required("journeyOrderId"), payment.required("data").required("businessRef"));
        var actions = source.required("approved").required("payload").required("approvedActions");
        assertEquals("COMPENSATION", actions.required("decisionKind").asText());
        assertEquals("PostSalesApplied", applied.eventType());
        assertEquals(source.required("approved").required("payload").required("caseId"),
            source.required("applied").required("payload").required("caseId"));
        assertEquals(payment.required("data").required("businessRef"),
            source.required("approved").required("payload").required("orderId"));
        long approvedMinor;
        if (actions.has("compensation")) {
            assertFalse(actions.has("refund"));
            assertEquals("WALLET_CREDIT", actions.at("/compensation/method").asText());
            assertEquals("COMPENSATION", source.required("applied").at("/payload/caseType").asText());
            assertEquals(0, payment.required("data").at("/refundedAmount/minorUnits").asLong());
            assertEquals(recordedCase.at("/decision/amountSnapshot/refundAmount"), actions.at("/compensation/amount"));
            approvedMinor = actions.at("/compensation/amount/minorUnits").asLong();
        } else {
            approvedMinor = actions.required("refund").required("amount").required("minorUnits").asLong();
        }
        assertTrue(approvedMinor > 0);
        assertTrue(payment.required("data").required("capturedAmount").required("minorUnits").asLong()
            - payment.required("data").required("refundedAmount").required("minorUnits").asLong() >= approvedMinor);

        String schema = "payment_compensation_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET search_path TO " + schema);
        config.addDataSourceProperty("options", "-c statement_timeout=5000 -c lock_timeout=2000");
        try (var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                var transactionManager = new DataSourceTransactionManager(pool);
                new MigrationRunner(pool, transactionManager).run(Path.of("migrations"));
                assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
                if (includePayment) {
                    jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                        payment.required("id").asText(), payment.required("version").asLong(), payment.required("data").toString());
                }
                var before = jdbc.queryForList("SELECT to_jsonb(p)::text FROM payment_intent_snapshots p ORDER BY id", String.class);
                for (int restart = 0; restart < 2; restart++) {
                    var commands = new PaymentCommandService(Clock.systemUTC(),
                        new TransactionalOutboxEventPublisher(new OutboxAppender(pool, mapper), mapper),
                        new PostgresPaymentIntentRepository(pool, mapper), new PostgresRefundRepository(pool, mapper),
                        new PostgresReservationPaymentRequestRepository(pool, mapper), new PostgresLatePaymentCaseRepository(pool, mapper),
                        new HttpPaymentChannelClient(mapper, RestClient.builder(), "http://127.0.0.1:1"));
                    var proxy = new ProxyFactory(new PaymentInboundEventHandler(new PostgresConsumedEventDeduplicator(pool), commands));
                    var advice = new TransactionInterceptor();
                    advice.setTransactionManager(transactionManager);
                    advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                    proxy.addAdvice(advice);
                    var handler = new PaymentEventFilter((EventSubscriber.EventHandler) proxy.getProxy());
                    for (var event : new EventEnvelope[]{applied, approved, approved, applied}) {
                        assertEquals(HandlerResult.SUCCESS, handler.handle(event));
                        assertEquals(before, jdbc.queryForList("SELECT to_jsonb(p)::text FROM payment_intent_snapshots p ORDER BY id", String.class));
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_snapshots", Integer.class));
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
                    }
                }
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                assertEquals(approved.eventId(), jdbc.queryForObject("SELECT event_id FROM processed_events", String.class));
                System.out.printf("Compensation payment: case=%s snapshot=%s refunds=0 outbox=0 replay=stable%n",
                    source.required("approved").required("payload").required("caseId").asText(), includePayment);
            } finally {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
