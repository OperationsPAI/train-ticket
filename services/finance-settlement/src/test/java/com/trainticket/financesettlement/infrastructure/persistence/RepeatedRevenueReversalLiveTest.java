package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_REPEATED_REFUND_FACTS",matches=".+")
class RepeatedRevenueReversalLiveTest {
    @Test
    void actualPartialRefundsAccumulateAndRemainIdempotentAfterReload() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var facts=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REPEATED_REFUND_FACTS"))));
        var payment=facts.path("payment");
        String schema="repeated_revenue_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                jdbc.execute(Files.readString(Path.of("migrations/001_finance_settlement_persistence.sql")));
                var repo=new PostgresFinanceRepositories.RevenueRecognitions(pool,mapper);
                var revenue=RevenueRecognition.recognize(payment.path("businessRef").asText(),payment.path("paymentIntentId").asText(),"fare",
                    money(payment.path("capturedAmount")),"payment-capture-v1",payment.path("paymentIntentId").asText(),Instant.now(),Instant.now(),"cmd-recognition","corr-recognition");
                repo.save(revenue);
                for(var refund:facts.path("refunds")) {
                    revenue=repo.findById(revenue.revenueRecognitionId()).orElseThrow();
                    revenue.reverse(refund.path("reason").asText(),refund.path("refundId").asText(),money(refund.path("amount")),Instant.now(),"cmd-reverse","cmd-reverse","corr-reverse");
                    repo.save(revenue);
                    var restored=repo.findById(revenue.revenueRecognitionId()).orElseThrow();
                    assertFalse(restored.reversed());
                    restored.reverse(refund.path("reason").asText(),refund.path("refundId").asText(),money(refund.path("amount")),Instant.now(),"cmd-retry","cmd-retry","corr-retry");
                    assertTrue(restored.domainEvents().isEmpty());
                    assertEquals(revenue.reversedAmount(),restored.reversedAmount());
                    var duplicate=restored;
                    assertThrows(DomainRuleViolation.class,()->duplicate.reverse("refund",refund.path("refundId").asText(),money(refund.path("amount")).plus(Money.of("CNY","0.01")),Instant.now(),"cmd","cmd","corr"));
                }
                var restored=repo.findById(revenue.revenueRecognitionId()).orElseThrow();
                assertEquals(money(payment.path("refundedAmount")),restored.reversedAmount());
                assertEquals(money(payment.path("capturedAmount")).minus(money(payment.path("refundedAmount"))),restored.netAmount());
                assertEquals(2,restored.reversalAmounts().size());
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static Money money(JsonNode node) {
        var currency=java.util.Currency.getInstance(node.path("currency").asText());
        return new Money(currency,java.math.BigDecimal.valueOf(node.path("minorUnits").asLong(),currency.getDefaultFractionDigits()));
    }
}
