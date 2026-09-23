package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.application.*;
import com.trainticket.financesettlement.adapters.http.ChangePaymentFactsClient;
import com.trainticket.financesettlement.domain.*;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_CHANGE_CAPTURE_FACTS",matches=".+")
class ChangePaymentOwnershipLiveTest {
    @Test
    void recordedChangePaymentUsesVerifiedOriginalOrder() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var facts=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_CAPTURE_FACTS"))));
        var event=mapper.treeToValue(facts.path("events").get(0),EventEnvelope.class);
        var currency=java.util.Currency.getInstance(facts.path("capturedAmount").path("currency").asText());
        var amount=new Money(currency,java.math.BigDecimal.valueOf(facts.path("capturedAmount").path("minorUnits").asLong(),currency.getDefaultFractionDigits()));
        var client=new ChangePaymentFactsClient(RestClient.builder(),System.getenv("TEST_REFERENCE_URL_TEMPLATE"));
        String caseId=facts.path("businessRef").asText(),paymentId=facts.path("paymentIntentId").asText();
        String order=client.orderFor(caseId,paymentId,amount,event.correlationId());
        assertTrue(order.startsWith("ord-"));
        assertThrows(DomainRuleViolation.class,()->client.orderFor(caseId,paymentId,amount.plus(Money.of("CNY","0.01")),event.correlationId()));
        String schema="change_revenue_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                var revenues=new PostgresFinanceRepositories.RevenueRecognitions(pool,mapper);
                var projections=new PostgresFinanceSettlementProjectionRepository(pool);
                var refs=new PostgresReferenceRepositories.PaymentIntentRefs(pool);
                var service=new FinanceSettlementApplicationService(revenues,new PostgresFinanceRepositories.Cases(pool,mapper),new PostgresFinanceRepositories.Invoices(pool,mapper),
                    projections,new PostgresFinanceRepositories.Batches(pool,mapper),new PostgresFinanceRepositories.Suppliers(pool,mapper),new PostgresFinanceRepositories.FeeAccruals(pool,mapper),
                    new TransactionalOutboxEventPublisher(new OutboxAppender(pool,mapper),mapper),new DomainEventEnvelopeMapper(),Clock.systemUTC());
                var handler=new FinanceSettlementEventHandler(new PostgresConsumedEventLogRepository(pool),refs,new PostgresReferenceRepositories.SegmentBookingRefs(pool),projections,Clock.systemUTC(),service).withChangePayments(client);
                var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
                for(int attempt=0;attempt<2;attempt++)assertEquals(HandlerResult.SUCCESS,tx.execute(status->handler.handle(event)));
                assertTrue(revenues.findByOrderId(caseId).isEmpty());
                var revenue=revenues.findByOrderId(order).getFirst();
                assertEquals(amount,revenue.amount());assertEquals(paymentId,revenue.orderItemId());
                assertEquals("change-payment",revenue.componentCode());assertEquals(order,refs.findOrderReference(paymentId).orElseThrow());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM revenue_recognition_snapshots",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RevenueRecognized'",Integer.class));
                assertTrue(projections.findCapture(order).isEmpty());
                var paidCase=client.read("post-sales","/api/v1/post-sales-cases/"+caseId,event.correlationId());
                var applied=applied(paidCase,event.correlationId());
                for(int attempt=0;attempt<2;attempt++)assertEquals(HandlerResult.SUCCESS,tx.execute(status->handler.handle(applied)));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM fee_accrual_snapshots WHERE data->>'sourceEventId'=?",Integer.class,caseId));
                String lowerCaseId=System.getenv("TEST_LOWER_CHANGE_CASE");
                var lowerCase=client.read("post-sales","/api/v1/post-sales-cases/"+lowerCaseId,event.correlationId());
                String lowerOrder=lowerCase.path("journeyOrderId").asText();
                var original=client.read("payment","/api/v1/payment-intents/by-business/"+lowerOrder,event.correlationId());
                var originalFacts=client.read("payment","/api/v1/internal/payment-intents/"+original.path("paymentIntentId").asText()+"/captured-facts",event.correlationId());
                var originalEvent=mapper.treeToValue(originalFacts.path("events").get(0),EventEnvelope.class);
                assertEquals(HandlerResult.SUCCESS,tx.execute(status->handler.handle(originalEvent)));
                var lowerApplied=applied(lowerCase,event.correlationId());
                for(int attempt=0;attempt<2;attempt++)assertEquals(HandlerResult.SUCCESS,tx.execute(status->handler.handle(lowerApplied)));
                var lowerRevenue=revenues.findByOrderId(lowerOrder).getFirst();
                long expectedRefund=lowerCase.path("decision").path("refundableAmount").path("minorUnits").asLong();
                assertEquals(expectedRefund,lowerRevenue.reversedAmount().amount().movePointRight(2).longValueExact());
                assertFalse(lowerRevenue.reversed());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RevenueRecognitionReversed'",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM fee_accrual_snapshots WHERE data->>'sourceEventId'=?",Integer.class,lowerCaseId));
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static EventEnvelope applied(com.fasterxml.jackson.databind.JsonNode recorded,String correlation) {
        return new EventEnvelope(com.trainticket.platformkit.messaging.PrefixedIds.newEventId(),"PostSalesApplied",java.time.Instant.now(),correlation,null,"post-sales",1,
            java.util.Map.of("caseId",recorded.path("caseId").asText(),"orderId",recorded.path("journeyOrderId").asText(),"caseType",recorded.path("caseType").asText()));
    }
}
