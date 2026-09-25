package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.adapters.http.ChangePaymentFactsClient;
import com.trainticket.financesettlement.application.DomainEventEnvelopeMapper;
import com.trainticket.financesettlement.application.EventSubscriber;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.HandlerResult;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MixedFareDifferenceRefundsLiveTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);
    private static final String ORDER_ID = "ord-mixed-refunds";
    private static final String CASE_ID = "psc-mixed-refunds";

    @Test
    void mixedPaymentsAndRepeatedRefundsRemainExactAfterRestart() throws Exception {
        withDatabase((pool, jdbc, mapper) -> {
            seedRevenue(pool, mapper, "pi-original", "fare", "100.00", "evt-original-capture", "2026-09-24T08:00:00Z");
            seedRevenue(pool, mapper, "pi-change-1", "change-payment", "20.00", "evt-change-capture-1", "2026-09-24T09:00:00Z");
            seedRevenue(pool, mapper, "pi-change-2", "change-payment", "30.00", "evt-change-capture-2", "2026-09-24T10:00:00Z");

            var facts = new FakePaymentFacts(mapper, CASE_ID, ORDER_ID, 4_500);
            facts.capture("pi-original", 10_000, 1_500, "evt-original-capture");
            facts.capture("pi-change-1", 2_000, 2_000, "evt-change-capture-1");
            facts.capture("pi-change-2", 3_000, 1_000, "evt-change-capture-2");
            facts.refund("rf-original-1", "pi-original", 1_000);
            facts.refund("rf-original-2", "pi-original", 500);
            facts.refund("rf-change-1", "pi-change-1", 2_000);
            facts.refund("rf-change-2", "pi-change-2", 1_000);

            EventSubscriber.EventHandler first = handler(pool, mapper, facts);
            assertEquals(HandlerResult.SUCCESS, first.handle(appliedEvent()));

            var reloaded = new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper);
            assertRefund(reloaded, "pi-original", Money.of("CNY", "15.00"), 2);
            assertRefund(reloaded, "pi-change-1", Money.of("CNY", "20.00"), 1);
            assertRefund(reloaded, "pi-change-2", Money.of("CNY", "10.00"), 1);
            assertEquals(Money.of("CNY", "105.00"), netRevenue(reloaded));
            assertEquals(4, reversalOutboxCount(jdbc));

            EventSubscriber.EventHandler restarted = handler(pool, mapper, facts);
            assertEquals(HandlerResult.SUCCESS, restarted.handle(appliedEvent()));
            assertEquals(Money.of("CNY", "105.00"), netRevenue(new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper)));
            assertEquals(4, reversalOutboxCount(jdbc));

            facts.changeRefundAmount("rf-original-2", 600);
            facts.changeRefundAmount("rf-change-2", 900);
            assertEquals(HandlerResult.FATAL_FAILURE, restarted.handle(appliedEvent()));
            assertEquals(Money.of("CNY", "105.00"), netRevenue(new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper)));
            assertEquals(4, reversalOutboxCount(jdbc));
        });
    }

    @Test
    void refundsForOnePaymentCannotExceedItsAuthoritativeRefundedTotal() throws Exception {
        withDatabase((pool, jdbc, mapper) -> {
            seedRevenue(pool, mapper, "pi-original", "fare", "100.00", "evt-original-capture", "2026-09-24T08:00:00Z");
            seedRevenue(pool, mapper, "pi-change-1", "change-payment", "20.00", "evt-change-capture-1", "2026-09-24T09:00:00Z");

            var facts = new FakePaymentFacts(mapper, CASE_ID, ORDER_ID, 2_000);
            facts.capture("pi-original", 10_000, 1_500, "evt-original-capture");
            facts.refund("rf-original-1", "pi-original", 1_000);
            facts.refund("rf-original-2", "pi-original", 1_000);

            assertEquals(HandlerResult.FATAL_FAILURE, handler(pool, mapper, facts).handle(appliedEvent()));
            assertEquals(Money.of("CNY", "120.00"), netRevenue(new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper)));
            assertEquals(0, reversalOutboxCount(jdbc));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
        });
    }

    private static EventSubscriber.EventHandler handler(HikariDataSource pool, ObjectMapper mapper, FakePaymentFacts facts) {
        var projections = new PostgresFinanceSettlementProjectionRepository(pool);
        var service = new FinanceSettlementApplicationService(
            new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper),
            new PostgresFinanceRepositories.Cases(pool, mapper),
            new PostgresFinanceRepositories.Invoices(pool, mapper),
            projections,
            new PostgresFinanceRepositories.Batches(pool, mapper),
            new PostgresFinanceRepositories.Suppliers(pool, mapper),
            new PostgresFinanceRepositories.FeeAccruals(pool, mapper),
            new TransactionalOutboxEventPublisher(new OutboxAppender(pool, mapper), mapper),
            new DomainEventEnvelopeMapper(),
            CLOCK
        );
        var target = new FinanceSettlementEventHandler(
            new PostgresConsumedEventLogRepository(pool),
            new PostgresReferenceRepositories.PaymentIntentRefs(pool),
            new PostgresReferenceRepositories.SegmentBookingRefs(pool),
            projections,
            CLOCK,
            service
        ).withChangePayments(facts);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(pool));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(advice);
        return (EventSubscriber.EventHandler) proxy.getProxy();
    }

    private static void seedRevenue(HikariDataSource pool, ObjectMapper mapper, String paymentId, String component,
                                    String amount, String sourceEventId, String recognizedAt) {
        var recognition = RevenueRecognition.recognize(
            ORDER_ID,
            paymentId,
            component,
            Money.of("CNY", amount),
            component + "-v1",
            sourceEventId,
            Instant.parse(recognizedAt),
            CLOCK.instant(),
            PrefixedIds.newCommandId(),
            PrefixedIds.newCorrelationId()
        );
        new PostgresFinanceRepositories.RevenueRecognitions(pool, mapper).save(recognition);
    }

    private static void assertRefund(PostgresFinanceRepositories.RevenueRecognitions repository, String paymentId,
                                     Money expected, int references) {
        RevenueRecognition recognition = repository.findByOrderId(ORDER_ID).stream()
            .filter(item -> paymentId.equals(item.orderItemId()))
            .findFirst()
            .orElseThrow();
        assertEquals(expected, recognition.reversedAmount());
        assertEquals(references, recognition.reversalAmounts().size());
    }

    private static Money netRevenue(PostgresFinanceRepositories.RevenueRecognitions repository) {
        return repository.findByOrderId(ORDER_ID).stream()
            .map(RevenueRecognition::netAmount)
            .reduce(Money.of("CNY", "0.00"), Money::plus);
    }

    private static int reversalOutboxCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RevenueRecognitionReversed'",
            Integer.class
        );
    }

    private static EventEnvelope appliedEvent() {
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            "PostSalesApplied",
            CLOCK.instant(),
            PrefixedIds.newCorrelationId(),
            null,
            "post-sales",
            1,
            Map.of("caseId", CASE_ID, "orderId", ORDER_ID, "caseType", "REFUND")
        );
    }

    private static void withDatabase(DatabaseTest body) throws Exception {
        String schema = "mixed_refunds_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET search_path TO " + schema);
        try (var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                try (var files = Files.list(Path.of("migrations"))) {
                    for (var file : files.sorted().toList()) jdbc.execute(Files.readString(file));
                }
                body.run(pool, jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
            } finally {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface DatabaseTest {
        void run(HikariDataSource pool, JdbcTemplate jdbc, ObjectMapper mapper) throws Exception;
    }

    private static final class FakePaymentFacts extends ChangePaymentFactsClient {
        private final ObjectMapper mapper;
        private final ObjectNode change;
        private final ArrayNode refunds;
        private final Map<String, ObjectNode> captures = new LinkedHashMap<>();

        private FakePaymentFacts(ObjectMapper mapper, String caseId, String orderId, long approvedRefundMinorUnits) {
            super(RestClient.builder(), "http://unused-%s");
            this.mapper = mapper;
            this.change = mapper.createObjectNode();
            change.put("caseId", caseId);
            change.put("journeyOrderId", orderId);
            change.put("status", "APPLIED");
            ObjectNode decision = change.putObject("decision");
            money(decision.putObject("refundableAmount"), approvedRefundMinorUnits);
            money(decision.putObject("refundAssessment").putObject("penaltyAmount"), 0);
            this.refunds = mapper.createArrayNode();
        }

        private void capture(String paymentId, long capturedMinorUnits, long refundedMinorUnits, String sourceEventId) {
            ObjectNode facts = mapper.createObjectNode();
            facts.put("paymentIntentId", paymentId);
            money(facts.putObject("capturedAmount"), capturedMinorUnits);
            money(facts.putObject("refundedAmount"), refundedMinorUnits);
            facts.putArray("events").addObject().put("eventId", sourceEventId);
            captures.put(paymentId, facts);
        }

        private void refund(String refundId, String paymentId, long amountMinorUnits) {
            ObjectNode refund = refunds.addObject();
            refund.put("refundId", refundId);
            refund.put("paymentIntentId", paymentId);
            refund.put("businessCaseRef", CASE_ID);
            refund.put("status", "SETTLED");
            money(refund.putObject("amount"), amountMinorUnits);
        }

        private void changeRefundAmount(String refundId, long amountMinorUnits) {
            for (JsonNode refund : refunds) {
                if (refundId.equals(refund.path("refundId").asText())) {
                    money((ObjectNode) refund.path("amount"), amountMinorUnits);
                    return;
                }
            }
            throw new IllegalArgumentException("unknown refund " + refundId);
        }

        @Override
        public JsonNode read(String service, String path, String correlation) {
            if ("post-sales".equals(service) && path.equals("/api/v1/post-sales-cases/" + CASE_ID)) return change.deepCopy();
            if ("payment".equals(service) && path.equals("/api/v1/refunds?businessCaseRef=" + CASE_ID)) {
                ObjectNode result = mapper.createObjectNode();
                result.set("items", refunds.deepCopy());
                result.put("total", refunds.size());
                return result;
            }
            String prefix = "/api/v1/internal/payment-intents/";
            String suffix = "/captured-facts";
            if ("payment".equals(service) && path.startsWith(prefix) && path.endsWith(suffix)) {
                String paymentId = path.substring(prefix.length(), path.length() - suffix.length());
                ObjectNode facts = captures.get(paymentId);
                if (facts != null) return facts.deepCopy();
            }
            throw new IllegalArgumentException("unexpected reference lookup " + service + path);
        }

        private static void money(ObjectNode node, long minorUnits) {
            node.put("currency", "CNY");
            node.put("minorUnits", minorUnits);
        }
    }
}
