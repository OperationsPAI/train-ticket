package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.ChangeAssessment;
import com.trainticket.postsales.domain.ChangeFlowSnapshot;
import com.trainticket.postsales.domain.ChangeFinancialAction;
import com.trainticket.postsales.domain.ChangePolicyEngine;
import com.trainticket.postsales.domain.DomainRuleViolation;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChangeReservationTimeoutTest {
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Test
    void executingChangeWhoseReplacementNeverConfirmedBecomesTerminal() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        PostSalesApplicationService service = service(repository);
        PostSalesCase change = change("case-timeout", PostSalesCaseStatus.EXECUTING);
        change.recordChangeOperation("target", Map.of("offer", Map.of("offerId", "off-timeout"), "accountId", "acct-1"));
        change.recordChangeOperation(
            "booking:seg-1:tvl-1",
            Map.of("segmentBookingId", "sb-1", "status", "REQUESTED", "requestedAt", NOW.toString())
        );
        repository.save(change);

        PostSalesCase failed = service.failReservationTimeout("psc-case-timeout", "corr-change-timeout");

        assertEquals(PostSalesCaseStatus.FAILED, failed.status());
        assertEquals("CHANGE_RESERVATION_TIMED_OUT", failed.terminalReason());
        assertEquals("sb-1", failed.changeOperations().get("booking:seg-1:tvl-1").get("segmentBookingId"));
    }

    @Test
    void appliedChangeIsNotFailedByTheTimeoutPath() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        PostSalesApplicationService service = service(repository);
        PostSalesCase applied = change("case-applied", PostSalesCaseStatus.APPLIED);
        repository.save(applied);

        assertThrows(DomainRuleViolation.class, () -> service.failReservationTimeout("psc-case-applied", "corr-applied"));
    }

    @Test
    void reservationWaitExpiresOnlyAfterTheSagaDeadline() {
        Map<String, Object> operation = Map.of("requestedAt", NOW.toString());

        assertFalse(ChangeExecutionService.reservationWaitExpired(operation, NOW.plusSeconds(600)));
        assertFalse(ChangeExecutionService.reservationWaitExpired(operation, NOW.plusSeconds(900)));
        assertTrue(ChangeExecutionService.reservationWaitExpired(operation, NOW.plusSeconds(901)));
        assertFalse(
            ChangeExecutionService.reservationWaitExpired(Map.of("segmentBookingId", "sb-1"), NOW.plusSeconds(86_400)),
            "an operation without a recorded request time never times out"
        );
    }

    private static PostSalesApplicationService service(InMemoryPostSalesRepository repository) {
        return new PostSalesApplicationService(
            repository,
            ignored -> { },
            ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static PostSalesCase change(String caseId, PostSalesCaseStatus status) {
        PostSalesCase change = PostSalesCase.rehydrate(
            caseId,
            "ord-timeout",
            PostSalesCaseType.CHANGE,
            scope(),
            "CHANGE_DATE",
            "acct-1",
            "idem-" + caseId,
            status,
            decision(caseId),
            java.util.List.of(),
            null,
            null,
            java.util.List.of(),
            NOW
        );
        change.withTargetOfferId("off-timeout");
        return change;
    }

    private static PostSalesScope scope() {
        return PostSalesScope.ticket("sb-1", "seg-1", "tvl-1", "ent-1");
    }

    private static PostSalesDecision decision(String caseId) {
        return PostSalesDecision.change(
            caseId,
            true,
            "CHANGE_ALLOWED",
            new RuleEvaluationSnapshot("fare-eval-1", "offer-rule-snapshot-1", "rule-v2026", NOW, Map.of("orderId", "ord-timeout")),
            AmountDecisionSnapshot.extraCharge(Money.of("0.00", "CNY"), Money.of("10.00", "CNY"), "change difference"),
            new ChangeFlowSnapshot("off-timeout", "hold-new-seat-1", ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT, "void-old-ticket-1", "issue-new-ticket-1", "release-old-seat-1"),
            new ChangePolicyEngine().evaluateChange(Money.of("50.00", "CNY"), Money.of("60.00", "CNY"), NOW, NOW.plusSeconds(10 * 86_400), 0),
            NOW.plusSeconds(2),
            NOW.plusSeconds(600)
        );
    }
}
