package com.trainticket.postsales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.DecisionKind;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import com.trainticket.postsales.observability.PostSalesMetricLabels;
import com.trainticket.postsales.observability.RecordingPostSalesMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompensationOutcomeRecordingTest {
    private static final Instant NOW = Instant.parse("2026-07-05T10:30:00Z");

    @Test
    void recordsTheApprovedCompensationStepsAsBoundedOutcomes() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        PostSalesCase compensation = executingCompensationCase();
        repository.save(compensation);
        String caseId = "psc-" + compensation.caseId();

        RecordingPostSalesMetrics recorder = new RecordingPostSalesMetrics();
        PostSalesApplicationService service = service(repository);
        service.setPostSalesMetrics(recorder);

        service.recordCompensationOperation(caseId, "request",
            Map.of("amount", Map.of("currency", "CNY", "minorUnits", 1_000)));
        // The issued evidence carries only the wallet benefit id, so the
        // currency falls back to the approved decision.
        service.recordCompensationOperation(caseId, "issued", Map.of("benefitId", "ben-1"));
        service.recordCompensationOperation(caseId, "verified",
            Map.of("amount", Map.of("currency", "CNY", "minorUnits", 1_000)));

        assertThat(recorder.compensations()).extracting(RecordingPostSalesMetrics.Compensation::outcome)
            .containsExactly(
                PostSalesMetricLabels.Outcome.PENDING,
                PostSalesMetricLabels.Outcome.SUCCEEDED,
                PostSalesMetricLabels.Outcome.VERIFIED);
        assertThat(recorder.compensations()).extracting(RecordingPostSalesMetrics.Compensation::currency)
            .containsOnly("CNY");
    }

    @Test
    void unknownOperationNamesCollapseToOther() {
        assertThat(PostSalesMetricLabels.compensationOutcome("request"))
            .isEqualTo(PostSalesMetricLabels.Outcome.PENDING);
        assertThat(PostSalesMetricLabels.compensationOutcome("issued"))
            .isEqualTo(PostSalesMetricLabels.Outcome.SUCCEEDED);
        assertThat(PostSalesMetricLabels.compensationOutcome("verified"))
            .isEqualTo(PostSalesMetricLabels.Outcome.VERIFIED);
        assertThat(PostSalesMetricLabels.compensationOutcome("case-specific-step-42"))
            .isEqualTo(PostSalesMetricLabels.Outcome.OTHER);
        assertThat(PostSalesMetricLabels.currencyOf(Map.of("currency", "not-a-currency")))
            .isEqualTo("other");
    }

    private static PostSalesApplicationService service(InMemoryPostSalesRepository repository) {
        return new PostSalesApplicationService(
            repository,
            ignored -> { },
            ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PostSalesCase executingCompensationCase() {
        PostSalesCase source = PostSalesCase.open(
            "ord-compensation",
            PostSalesCaseType.COMPENSATION,
            PostSalesScope.ticket("sb-comp", "seg-comp", "tvl-comp", "ent-comp"),
            "SERVICE_QUALITY_COMPLAINT",
            "acct-comp",
            "idem-compensation",
            NOW,
            "cmd-open",
            "corr-compensation");
        source.beginEvaluation(NOW, "cmd-evaluate", "cmd-open", "corr-compensation");
        source.recordDecision(new PostSalesDecision(
            source.caseId(),
            1,
            DecisionKind.COMPENSATION,
            true,
            "ELIGIBLE",
            new RuleEvaluationSnapshot("quote-comp", "rule-comp", "v1", NOW, Map.of("journeyOrderId", source.journeyOrderId())),
            AmountDecisionSnapshot.refund(Money.of("0", "CNY"), Money.of("10", "CNY"), "approved compensation"),
            null,
            null,
            null,
            NOW,
            NOW.plusSeconds(900)), false, false, NOW, "cmd-quote", "cmd-evaluate", "corr-compensation");
        source.approve("approval-comp", NOW, "cmd-approve", "cmd-quote", "corr-compensation");
        source.requestExecution(NOW, "cmd-execute", "cmd-approve", "corr-compensation");
        assertThat(source.status()).isEqualTo(PostSalesCaseStatus.EXECUTING);
        return source;
    }
}
