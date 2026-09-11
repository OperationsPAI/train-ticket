package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Capacity release has to find the one case scoped to a segment booking without
 * loading the others. On the integration cluster {@code post_sales_case_snapshots}
 * held 202,372 rows / 514 MB, and deserializing all of them per
 * {@code CapacityReleased} exhausted the heap: "Ran out of memory retrieving query
 * results" followed by an OutOfMemoryError and CrashLoopBackOff.
 */
class PostSalesCapacityReleaseLookupTest {
    private static final Instant NOW = Instant.parse("2026-07-05T10:30:00Z");

    @Test
    void capacityReleaseLooksUpOnlyTheCasesScopedToTheSegmentBooking() {
        CountingRepository repository = new CountingRepository();
        PostSalesApplicationService service = service(repository);
        repository.save(approvedCaseFor("case-match", "sb-match"));
        repository.save(approvedCaseFor("case-other", "sb-other"));

        service.applyForSegmentBooking("sb-match", "evt-cause", "corr-1");

        assertEquals(1, repository.lookups.get(), "the release must issue one scoped lookup");
        assertEquals(List.of("sb-match"), repository.lookedUpRefs);
        assertEquals(
            PostSalesCaseStatus.APPLIED,
            repository.findById("case-match").orElseThrow().status()
        );
        assertEquals(
            PostSalesCaseStatus.APPROVED,
            repository.findById("case-other").orElseThrow().status(),
            "a case scoped to another segment booking must be untouched"
        );
    }

    @Test
    void scopedLookupReturnsOnlyTheMatchingCases() {
        InMemoryPostSalesRepository repository = new InMemoryPostSalesRepository();
        repository.save(approvedCaseFor("case-match", "sb-match"));
        repository.save(approvedCaseFor("case-other", "sb-other"));

        List<PostSalesCase> found = repository.findByOrderItemRef("sb-match");

        assertEquals(1, found.size());
        assertEquals("case-match", found.get(0).caseId());
        assertTrue(repository.findByOrderItemRef("sb-absent").isEmpty());
    }

    private static PostSalesCase approvedCaseFor(String caseId, String orderItemRef) {
        return PostSalesCase.rehydrate(
            caseId,
            "ord-" + caseId,
            PostSalesCaseType.REFUND,
            PostSalesScope.ticket(orderItemRef, "seg-1", "tvl-1", "ent-1"),
            "CUSTOMER_REQUEST",
            "acct-1",
            "idem-" + caseId,
            PostSalesCaseStatus.APPROVED,
            decisionFor(caseId),
            List.of(),
            null,
            null,
            List.of()
        );
    }

    private static PostSalesDecision decisionFor(String caseId) {
        return PostSalesDecision.refund(
            caseId,
            true,
            "REFUNDABLE",
            new RuleEvaluationSnapshot("fare-eval-1", "rule-snapshot-1", "rule-v1", NOW, Map.of("orderId", "ord-" + caseId)),
            AmountDecisionSnapshot.refund(Money.of("0.00", "CNY"), Money.of("5.00", "CNY"), "capacity release test"),
            NOW,
            NOW.plusSeconds(3_600)
        );
    }

    private static PostSalesApplicationService service(PostSalesRepository repository) {
        return new PostSalesApplicationService(
            repository,
            ignored -> { },
            ignored -> Optional.empty(),
            new InMemoryPostSalesPolicyContextStore(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    /** Delegates to the in-memory store and records how the release looked its case up. */
    private static final class CountingRepository implements PostSalesRepository {
        private final InMemoryPostSalesRepository delegate = new InMemoryPostSalesRepository();
        private final AtomicInteger lookups = new AtomicInteger();
        private final List<String> lookedUpRefs = new java.util.ArrayList<>();

        @Override public void save(PostSalesCase postSalesCase) {
            delegate.save(postSalesCase);
        }

        @Override public Optional<PostSalesCase> findById(String caseId) {
            return delegate.findById(caseId);
        }

        @Override public Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override public Optional<PostSalesCase> findActiveRefundCaseForOrder(String journeyOrderId) {
            return delegate.findActiveRefundCaseForOrder(journeyOrderId);
        }

        @Override public List<PostSalesCase> findByOrderItemRef(String orderItemRef) {
            lookups.incrementAndGet();
            lookedUpRefs.add(orderItemRef);
            return delegate.findByOrderItemRef(orderItemRef);
        }
    }
}
