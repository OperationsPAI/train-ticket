package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

class DecisionSnapshotTest {
    @ParameterizedTest
    @EnumSource(value = DecisionKind.class, names = {"REFUND", "CHANGE", "COMPENSATION"})
    @EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
    void persistsCompleteAssessmentAndRejectsMissingFields(DecisionKind kind) throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "decision_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var dataSource = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
                var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                var repository = new PostgresPostSalesRepository(dataSource, mapper);
                Instant now = Instant.now();
                var source = PostSalesCase.open("order", PostSalesCaseType.valueOf(kind.name()),
                    PostSalesScope.ticket("booking", "segment", "traveler", "entitlement"),
                    "CUSTOMER_REQUEST", "account", UUID.randomUUID().toString(), now, "cmd-open", "corr");
                source.beginEvaluation(now, "cmd-evaluate", "cmd-open", "corr");
                var rules = new RuleEvaluationSnapshot("quote", "rules", "1", now, Map.of("orderId", "order"));
                var fare = Money.of("100", "CNY");
                var zero = Money.of("0", "CNY");
                PostSalesDecision decision;
                if (kind == DecisionKind.REFUND) {
                    var assessment = new RefundPolicyEngine().evaluateRefund(fare, now, now.plusSeconds(10 * 86400), false, "ADULT", 1);
                    var amount = AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(), assessment.explanation(), assessment.componentDecisions());
                    decision = PostSalesDecision.refund(source.caseId(), true, "ELIGIBLE", rules, amount, assessment, now, now.plusSeconds(600));
                } else if (kind == DecisionKind.CHANGE) {
                    var assessment = new ChangePolicyEngine().evaluateChange(fare, Money.of("120", "CNY"), now, now.plusSeconds(10 * 86400), 0);
                    var amount = AmountDecisionSnapshot.extraCharge(assessment.changeFee(), assessment.netPayable(), assessment.explanation());
                    var flow = new ChangeFlowSnapshot("offer", "hold", ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT, "void", "issue", "release");
                    decision = PostSalesDecision.change(source.caseId(), true, "ELIGIBLE", rules, amount, flow, assessment, now, now.plusSeconds(600));
                } else {
                    decision = new PostSalesDecision(source.caseId(), 1, kind, true, "ELIGIBLE", rules,
                        AmountDecisionSnapshot.refund(zero, fare, "compensation"), null, null, null, now, now.plusSeconds(600));
                }
                source.recordDecision(decision, false, false, now, "cmd-quote", "cmd-evaluate", "corr");
                repository.save(source);
                assertEquals(decision, new PostgresPostSalesRepository(dataSource, mapper).findById(source.caseId()).orElseThrow().decision());
                String snapshot = jdbc.queryForObject("SELECT data::text FROM post_sales_case_snapshots", String.class);
                for (String field : List.of("refundAssessment", "changeAssessment", "componentDecisions")) {
                    var root = (ObjectNode) mapper.readTree(snapshot);
                    var parent = (ObjectNode) root.path("decision");
                    if (field.equals("componentDecisions")) parent = (ObjectNode) parent.path("amountSnapshot");
                    parent.remove(field);
                    jdbc.update("UPDATE post_sales_case_snapshots SET data = ?::jsonb", root.toString());
                    var error = assertThrows(IllegalArgumentException.class, () -> repository.findById(source.caseId()));
                    assertTrue(error.getMessage().contains(field), error.getMessage());
                }
                if (kind != DecisionKind.COMPENSATION) {
                    String field = kind == DecisionKind.REFUND ? "refundAssessment" : "changeAssessment";
                    var root = (ObjectNode) mapper.readTree(snapshot);
                    ((ObjectNode) root.path("decision")).putNull(field);
                    jdbc.update("UPDATE post_sales_case_snapshots SET data = ?::jsonb", root.toString());
                    assertThrows(DomainRuleViolation.class, () -> repository.findById(source.caseId()));
                }
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
