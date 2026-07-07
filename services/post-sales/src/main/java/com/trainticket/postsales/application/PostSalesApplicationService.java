package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.ChangeFinancialAction;
import com.trainticket.postsales.domain.ChangeFlowSnapshot;
import com.trainticket.postsales.domain.DecisionKind;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.PostSalesEvent;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PostSalesApplicationService {
    private final PostSalesRepository repository;
    private final EventPublisher eventPublisher;
    private final AdjustmentQuotePort adjustmentQuotePort;
    private final Clock clock;

    public PostSalesApplicationService(PostSalesRepository repository, EventPublisher eventPublisher,
            AdjustmentQuotePort adjustmentQuotePort, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.adjustmentQuotePort = adjustmentQuotePort;
        this.clock = clock;
    }

    public PostSalesCase open(OpenCaseCommand command) {
        return repository.findByIdempotencyKey(command.idempotencyKey())
            .orElseGet(() -> {
                Instant now = clock.instant();
                PostSalesCase postSalesCase = PostSalesCase.open(
                    command.journeyOrderId(),
                    command.caseType(),
                    command.scope(),
                    command.reasonCode(),
                    command.actorRef(),
                    command.commandId(),
                    now,
                    command.commandId(),
                    command.correlationId()
                );
                repository.save(postSalesCase);
                publishNewEvents(postSalesCase);
                return postSalesCase;
            });
    }

    public PostSalesCase evaluate(String caseId, String sourceCommandId, String correlationId) {
        PostSalesCase postSalesCase = get(caseId);
        if (postSalesCase.decision() != null) {
            return postSalesCase;
        }
        Instant now = clock.instant();
        if (postSalesCase.status().name().equals("OPENED")) {
            postSalesCase.beginEvaluation(now, sourceCommandId, sourceCommandId, correlationId);
        }
        PostSalesDecision decision = decisionFor(postSalesCase, now);
        postSalesCase.evaluateEligibility(decision.eligible(), decision.reasonCode(),
            decision.ruleSnapshot().farePricingEvaluationRef(), decision.ruleSnapshot().fareRuleVersion(),
            now, sourceCommandId, sourceCommandId, correlationId);
        if (decision.eligible()) {
            postSalesCase.recordDecision(decision, false, false, now, sourceCommandId, sourceCommandId, correlationId);
        }
        repository.save(postSalesCase);
        publishNewEvents(postSalesCase);
        return postSalesCase;
    }

    public PostSalesCase approve(String caseId, String sourceCommandId, String correlationId) {
        PostSalesCase postSalesCase = get(caseId);
        if (postSalesCase.status().name().equals("APPROVED")) {
            return postSalesCase;
        }
        if (postSalesCase.decision() == null) {
            evaluate(caseId, sourceCommandId, correlationId);
            postSalesCase = get(caseId);
        }
        postSalesCase.approve("http-approval", clock.instant(), sourceCommandId, sourceCommandId, correlationId);
        repository.save(postSalesCase);
        publishNewEvents(postSalesCase);
        return postSalesCase;
    }

    public PostSalesCase get(String caseId) {
        return repository.findById(PostSalesMapper.stripCasePrefix(caseId))
            .orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    private PostSalesDecision decisionFor(PostSalesCase postSalesCase, Instant now) {
        DecisionKind kind = switch (postSalesCase.caseType()) {
            case REFUND, CANCELLATION, REBOOK -> DecisionKind.REFUND;
            case CHANGE -> DecisionKind.CHANGE;
            case COMPENSATION -> DecisionKind.COMPENSATION;
        };
        String purpose = kind == DecisionKind.CHANGE ? "CHANGE" : "REFUND";
        // Idempotency-Key must be a bare UUID v7 (fare-pricing contract); the
        // case ID is one, and reusing it keys the quote to this case.
        var quote = adjustmentQuotePort.compute(new AdjustmentQuotePort.AdjustmentQuoteRequest(
            purpose,
            postSalesCase.scope().entitlementRefs(),
            postSalesCase.journeyOrderId(),
            postSalesCase.scope().segmentRefs(),
            PostSalesMapper.stripCasePrefix(postSalesCase.caseId())
        ));
        RuleEvaluationSnapshot ruleSnapshot = new RuleEvaluationSnapshot(
            quote.map(AdjustmentQuotePort.AdjustmentQuoteResult::adjustmentQuoteId)
                .orElse("adjq-" + postSalesCase.caseId()),
            "offer-rule-snapshot-" + postSalesCase.caseId(),
            "rule-v1",
            now,
            Map.of("journeyOrderId", postSalesCase.journeyOrderId(), "postSalesCaseId", postSalesCase.caseId())
        );
        Money zero = Money.zero("CNY");
        Money refundable = quote
            .map(q -> Money.fromMinorUnits(q.refundableMinorUnits(), q.refundableCurrency()))
            .orElse(zero);
        Money amountDue = quote
            .map(q -> Money.fromMinorUnits(q.amountDueMinorUnits(), q.amountDueCurrency()))
            .orElse(zero);
        String explanation = quote
            .map(q -> "fare-pricing adjustment quote " + q.adjustmentQuoteId())
            .orElse("fare-pricing unavailable; zero-amount fallback");
        AmountDecisionSnapshot amount = switch (kind) {
            case REFUND, CANCELLATION, COMPENSATION -> AmountDecisionSnapshot.refund(zero, refundable, explanation);
            case CHANGE -> amountDue.isZero() && !refundable.isZero()
                ? AmountDecisionSnapshot.refund(zero, refundable, explanation)
                : AmountDecisionSnapshot.extraCharge(zero, amountDue, explanation);
        };
        ChangeFlowSnapshot changeFlowSnapshot = kind == DecisionKind.CHANGE
            ? new ChangeFlowSnapshot(
                "change-offer-" + postSalesCase.caseId(),
                "capacity-hold-" + postSalesCase.caseId(),
                ChangeFinancialAction.NONE,
                "void-old-entitlement-" + postSalesCase.caseId(),
                "issue-new-entitlement-" + postSalesCase.caseId(),
                "release-old-capacity-" + postSalesCase.caseId()
            )
            : null;
        return new PostSalesDecision(postSalesCase.caseId(), 1, kind, true, "ELIGIBLE", ruleSnapshot, amount, changeFlowSnapshot, now, now.plusSeconds(900));
    }

    /**
     * Capacity release is the terminal execution signal for refund cases
     * (subscription table row 5): find the APPROVED case scoped to this
     * segment booking and mark it applied.
     */
    public void applyForSegmentBooking(String segmentBookingRef, String causationId, String correlationId) {
        for (PostSalesCase postSalesCase : repository.findAll()) {
            if (!postSalesCase.scope().orderItemRefs().contains(segmentBookingRef)) {
                continue;
            }
            java.time.Instant now = java.time.Instant.now(clock);
            if (postSalesCase.status() == com.trainticket.postsales.domain.PostSalesCaseStatus.APPROVED) {
                postSalesCase.startExecution(now, "cmd-capacity-released", causationId, correlationId);
            }
            if (postSalesCase.status() == com.trainticket.postsales.domain.PostSalesCaseStatus.EXECUTING) {
                postSalesCase.applyResult("capacity released; refund executed", now, "cmd-capacity-released", causationId, correlationId);
                repository.save(postSalesCase);
                publishNewEvents(postSalesCase);
            }
            return;
        }
    }

    private void publishNewEvents(PostSalesCase postSalesCase) {
        // Every command path loads a fresh aggregate instance, so domainEvents()
        // holds exactly the events recorded by the current command.
        for (PostSalesEvent event : postSalesCase.domainEvents()) {
            eventPublisher.publish(PostSalesMapper.toEnvelope(event, postSalesCase));
        }
    }

    public record OpenCaseCommand(
        String journeyOrderId,
        PostSalesCaseType caseType,
        PostSalesScope scope,
        String reasonCode,
        String actorRef,
        String idempotencyKey,
        String commandId,
        String correlationId
    ) { }
}
