package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class ScopedCapacityCompletionTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL", matches=".+")
    void allTravelersCapacityMustBeReleasedAcrossPersistenceReloads() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "post_sales_scope_" + UUID.randomUUID().toString().replace("-", "");
        try (var dataSource = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
                var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                var repository = new PostgresPostSalesRepository(dataSource, mapper);
                Instant now = Instant.now();
                var scope = new PostSalesScope(List.of("booking-1", "booking-2"), List.of("segment"),
                    List.of("traveler-1", "traveler-2"), List.of("entitlement-1", "entitlement-2"));
                var source = PostSalesCase.open("order-scope", PostSalesCaseType.REFUND, scope,
                    "CUSTOMER_REQUEST", "account", "scope-request", now, "cmd-create", "corr-scope");
                source.beginEvaluation(now, "cmd-evaluate", "cmd-create", "corr-scope");
                source.recordDecision(PostSalesDecision.refund(source.caseId(), true, "REFUNDABLE",
                    new RuleEvaluationSnapshot("quote", "rules", "1", now, Map.of("orderId", "order-scope")),
                    AmountDecisionSnapshot.refund(Money.of("0", "CNY"), Money.of("10", "CNY"), "refund"),
                    now, now.plusSeconds(600)), false, false, now, "cmd-decision", "cmd-evaluate", "corr-scope");
                source.approve("approval", now, "cmd-approve", "cmd-decision", "corr-scope");
                source.startExecution(now, "cmd-execute", "cmd-approve", "corr-scope");
                repository.save(source);
                var first = repository.findByOrderItemRef("booking-1").getFirst();
                assertFalse(first.recordCapacityReleased("booking-1"));
                assertFalse(first.recordCapacityReleased("booking-1"));
                assertThrows(DomainRuleViolation.class, () -> first.recordCapacityReleased("another-booking"));
                repository.save(first);
                var next = repository.findById(source.caseId()).orElseThrow();
                assertEquals(PostSalesCaseStatus.EXECUTING, next.status());
                assertEquals(Set.of("booking-1"), next.releasedOrderItems());
                assertTrue(next.recordCapacityReleased("booking-2"));
                next.applyResult("all scoped capacity released", now, "cmd-apply", "evt-release", "corr-scope");
                repository.save(next);
                assertEquals(PostSalesCaseStatus.APPLIED, repository.findById(source.caseId()).orElseThrow().status());
                assertEquals(Set.of("booking-1", "booking-2"), repository.findById(source.caseId()).orElseThrow().releasedOrderItems());
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
