package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.*;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompensationRecordedEventLiveTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordedAppliedPreservesRevenueBeforeApprovalAndAcrossReplay() throws Exception {
        var source = recorded();
        try (var database = new Database()) {
            database.capture(source.required("captured"));
            var before = database.businessState();
            var applied = mapper.treeToValue(source.required("applied"), EventEnvelope.class);
            for (int restart = 0; restart < 2; restart++) {
                var handler = database.handler();
                for (int replay = 0; replay < 2; replay++) {
                    assertEquals(HandlerResult.SUCCESS, handler.handle(applied));
                    assertEquals(before, database.businessState());
                    assertEquals(0, database.count("approved_refunds_by_case_id"));
                }
            }
            assertEquals(2, database.count("processed_events"));
            System.out.println("Compensation finance: applied before approval acknowledged, revenue and outbox unchanged on replay");
        }
    }

    @Test
    void recordedApprovalMustNotCreateRefundProjection() throws Exception {
        verifyNoRefundProjection(recorded());
    }

    @Test
    void historicalCompensationApprovalMustNotCreateRefundProjection() throws Exception {
        verifyNoRefundProjection(mapper.readTree(getClass().getResourceAsStream("/compensation-recorded.json")));
    }

    private void verifyNoRefundProjection(JsonNode source) throws Exception {
        try (var database = new Database()) {
            database.capture(source.required("captured"));
            var before = database.businessState();
            var approved = mapper.treeToValue(source.required("approved"), EventEnvelope.class);
            for (int restart = 0; restart < 2; restart++) {
                var handler = database.handler();
                assertEquals(HandlerResult.SUCCESS, handler.handle(approved));
                assertEquals(HandlerResult.SUCCESS, handler.handle(approved));
                assertEquals(before, database.businessState());
            }
            assertEquals(2, database.count("processed_events"));
            assertEquals(0, database.count("approved_refunds_by_case_id"),
                "COMPENSATION approval must not become an approved original payment refund");
        }
    }

    @Test
    void recordedApprovalThenAppliedDoesNotReverseOriginalRevenue() throws Exception {
        var source = recorded();
        try (var database = new Database()) {
            database.capture(source.required("captured"));
            var before = database.businessState();
            for (int restart = 0; restart < 2; restart++) {
                var handler = database.handler();
                for (String type : List.of("approved", "applied", "applied", "approved")) {
                    assertEquals(HandlerResult.SUCCESS, handler.handle(mapper.treeToValue(source.required(type), EventEnvelope.class)));
                    assertEquals(before, database.businessState());
                }
            }
            assertEquals(3, database.count("processed_events"));
            System.out.println("Compensation finance: approval and applied preserve original revenue, fees and outbox");
        }
    }

    @Test
    void recordedBenefitIssuedBooksExactCompensationCostWithoutReversingRevenue() throws Exception {
        var source = recorded();
        var recordedCase = source.required("case");
        var event = mapper.treeToValue(source.required("benefitIssued"), EventEnvelope.class);
        var payload = source.required("benefitIssued").required("payload");
        assertEquals("BenefitIssued", event.eventType());
        assertEquals("wallet-promotion", event.producer());
        assertEquals("COMPENSATION", recordedCase.required("caseType").asText());
        assertEquals("COMPENSATION", recordedCase.at("/decision/kind").asText());
        assertTrue(recordedCase.at("/decision/eligible").asBoolean());
        assertEquals("POST_SALES_COMP", payload.required("issuanceSource").asText());
        assertEquals("COMPENSATION_CREDIT", payload.required("benefitType").asText());
        assertEquals("PROMOTION_CREDIT", payload.required("balanceType").asText());
        assertEquals("psc-" + recordedCase.required("caseId").asText(), payload.required("caseId").asText());
        assertEquals(recordedCase.required("actorRef"), payload.required("accountId"));
        assertEquals(recordedCase.at("/decision/amountSnapshot/refundAmount"), payload.required("issuedAmount"));
        assertEquals(recordedCase.required("journeyOrderId"), source.required("captured").at("/payload/businessRef"));
        assertEquals(payload.required("accountId"), source.required("captured").at("/payload/payerRef"));
        long minor = payload.required("issuedAmount").required("minorUnits").asLong();
        assertTrue(minor > 0);
        var currency = java.util.Currency.getInstance(payload.required("issuedAmount").required("currency").asText());
        var expected = java.math.BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
        try (var database = new Database()) {
            database.capture(source.required("captured"));
            var before = database.businessState();
            for (int restart = 0; restart < 2; restart++) {
                var handler = database.handler();
                assertEquals(HandlerResult.SUCCESS, handler.handle(event));
                assertEquals(HandlerResult.SUCCESS, handler.handle(event));
                assertEquals(before, database.businessState());
                assertEquals(1, database.count("benefit_cost_entries"));
                var row = database.jdbc.queryForMap("SELECT * FROM benefit_cost_entries WHERE event_id=?", event.eventId());
                assertEquals(payload.required("benefitId").asText(), row.get("benefit_id"));
                assertEquals(payload.required("accountId").asText(), row.get("account_id"));
                assertEquals(payload.required("caseId").asText(), row.get("case_id"));
                assertEquals("POST_SALES_COMP", row.get("issuance_source"));
                assertEquals("BenefitIssued", row.get("event_type"));
                assertEquals(currency.getCurrencyCode(), row.get("currency"));
                assertEquals(0, expected.compareTo((java.math.BigDecimal) row.get("amount")));
                assertTrue(java.time.Duration.between(Instant.parse(payload.required("issuedAt").asText()),
                    ((java.sql.Timestamp) row.get("occurred_at")).toInstant()).abs().compareTo(java.time.Duration.ofNanos(1000)) <= 0);
            }
            assertEquals(0, database.count("approved_refunds_by_case_id"));
            assertEquals(2, database.count("processed_events"));
            System.out.printf("Compensation cost: case=%s benefit=%s currency=%s minorUnits=%d rows=1 revenue=unchanged%n",
                payload.required("caseId").asText(), payload.required("benefitId").asText(), currency, minor);
        }
    }

    private JsonNode recorded() throws Exception {
        var source = mapper.readTree(getClass().getResourceAsStream("/wallet-compensation-recorded.json"));
        var recordedCase = source.required("case");
        assertEquals("COMPENSATION", recordedCase.required("caseType").asText());
        assertEquals("APPLIED", recordedCase.required("status").asText());
        assertEquals("psc-" + recordedCase.required("caseId").asText(), source.required("applied").at("/payload/caseId").asText());
        assertEquals(recordedCase.required("journeyOrderId"), source.required("captured").at("/payload/businessRef"));
        assertEquals("COMPENSATION", source.required("approved").at("/payload/approvedActions/decisionKind").asText());
        assertEquals("COMPENSATION", source.required("applied").at("/payload/caseType").asText());
        assertEquals(source.required("approved").at("/payload/caseId"), source.required("applied").at("/payload/caseId"));
        assertEquals(source.required("captured").at("/payload/businessRef"), source.required("applied").at("/payload/orderId"));
        assertFalse(source.required("approved").at("/payload/approvedActions").has("refund"));
        assertEquals("WALLET_CREDIT", source.required("approved").at("/payload/approvedActions/compensation/method").asText());
        assertEquals(recordedCase.at("/decision/amountSnapshot/refundAmount"), source.required("approved").at("/payload/approvedActions/compensation/amount"));
        assertTrue(source.required("approved").at("/payload/approvedActions/compensation/amount/minorUnits").asLong() > 0);
        return source;
    }

    private final class Database implements AutoCloseable {
        private final String schema = "finance_compensation_" + UUID.randomUUID().toString().replace("-", "");
        private final HikariDataSource pool;
        private final JdbcTemplate jdbc;
        private final DataSourceTransactionManager manager;

        Database() {
            var config = new HikariConfig();
            config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
            config.setMaximumPoolSize(2);
            config.setConnectionInitSql("SET search_path TO " + schema);
            config.addDataSourceProperty("options", "-c statement_timeout=5000 -c lock_timeout=2000");
            pool = new HikariDataSource(config);
            jdbc = new JdbcTemplate(pool);
            manager = new DataSourceTransactionManager(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                new MigrationRunner(pool, manager).run(Path.of("migrations"));
                assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        EventSubscriber.EventHandler handler() {
            var projections = new PostgresFinanceSettlementProjectionRepository(pool);
            var service = new FinanceSettlementApplicationService(new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper),
                new PostgresFinanceRepositories.Cases(pool, mapper), new PostgresFinanceRepositories.Invoices(pool, mapper), projections,
                new PostgresFinanceRepositories.Batches(pool, mapper), new PostgresFinanceRepositories.Suppliers(pool, mapper),
                new PostgresFinanceRepositories.FeeAccruals(pool, mapper),
                new TransactionalOutboxEventPublisher(new OutboxAppender(pool, mapper), mapper), new DomainEventEnvelopeMapper(), Clock.systemUTC());
            var target = new FinanceSettlementEventHandler(new PostgresConsumedEventLogRepository(pool),
                new PostgresReferenceRepositories.PaymentIntentRefs(pool), new PostgresReferenceRepositories.SegmentBookingRefs(pool),
                projections, Clock.systemUTC(), service);
            var proxy = new ProxyFactory(target);
            var advice = new TransactionInterceptor();
            advice.setTransactionManager(manager);
            advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            proxy.addAdvice(advice);
            return (EventSubscriber.EventHandler) proxy.getProxy();
        }

        void capture(JsonNode event) throws Exception {
            assertEquals("PaymentCaptured", event.required("eventType").asText());
            assertEquals(HandlerResult.SUCCESS, handler().handle(mapper.treeToValue(event, EventEnvelope.class)));
            assertEquals(1, count("revenue_recognition_snapshots"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM revenue_recognition_snapshots WHERE data->>'reversed'='true'", Integer.class));
        }

        Map<String, List<String>> businessState() {
            var result = new LinkedHashMap<String, List<String>>();
            for (String table : List.of("revenue_recognition_snapshots", "fee_accrual_snapshots", "reconciliation_case_snapshots",
                "invoice_snapshots", "captures_by_order_id", "payment_intent_order_refs", "outbox")) {
                result.put(table, jdbc.queryForList("SELECT to_jsonb(r)::text FROM " + table + " r ORDER BY to_jsonb(r)::text", String.class));
            }
            return result;
        }

        int count(String table) {
            return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        }

        @Override
        public void close() {
            try {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                pool.close();
            }
        }
    }
}
