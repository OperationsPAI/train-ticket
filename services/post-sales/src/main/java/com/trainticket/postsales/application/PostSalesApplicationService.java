package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.AmountDecisionSnapshot;
import com.trainticket.postsales.domain.ChangeAssessment;
import com.trainticket.postsales.domain.ChangeFinancialAction;
import com.trainticket.postsales.domain.ChangeFlowSnapshot;
import com.trainticket.postsales.domain.ChangePolicyEngine;
import com.trainticket.postsales.domain.DecisionKind;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesDecision;
import com.trainticket.postsales.domain.RefundAssessment;
import com.trainticket.postsales.domain.RefundClassification;
import com.trainticket.postsales.domain.RefundPolicyEngine;
import com.trainticket.postsales.domain.PostSalesEvent;
import com.trainticket.postsales.domain.RefundWaterfall;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
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
    private final RefundPolicyEngine refundPolicyEngine = new RefundPolicyEngine();
    private final ChangePolicyEngine changePolicyEngine = new ChangePolicyEngine();

    public PostSalesApplicationService(PostSalesRepository repository, EventPublisher eventPublisher,
            AdjustmentQuotePort adjustmentQuotePort, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.adjustmentQuotePort = adjustmentQuotePort;
        this.clock = clock;
    }

    public PostSalesCase open(OpenCaseCommand command) {
        return repository.findByIdempotencyKey(command.idempotencyKey())
            .orElseGet(() -> openNewCase(command));
    }

    public PostSalesCase evaluate(String caseId, String sourceCommandId, String correlationId) {
        PostSalesCase postSalesCase = get(caseId);
        if (isTerminalNonRefundable(postSalesCase)) {
            throw new OrderNotRefundableException();
        }
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
        if (isTerminalNonRefundable(postSalesCase)) {
            throw new OrderNotRefundableException();
        }
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


    private PostSalesCase openNewCase(OpenCaseCommand command) {
        if (requiresExclusiveRefundSlot(command.caseType())) {
            repository.findActiveRefundCaseForOrder(command.journeyOrderId())
                .ifPresent(existing -> {
                    throw new RefundAlreadyInProgressException(existing.caseId());
                });
        }
        Instant now = clock.instant();
        PostSalesCase postSalesCase = PostSalesCase.open(
            command.journeyOrderId(),
            command.caseType(),
            command.scope(),
            command.reasonCode(),
            command.actorRef(),
            command.idempotencyKey(),
            now,
            command.commandId(),
            command.correlationId()
        );
        try {
            repository.save(postSalesCase);
        } catch (OptimisticConcurrencyException exception) {
            throw new PostSalesConcurrencyException("Concurrent post-sales update conflicted", exception);
        }
        publishNewEvents(postSalesCase);
        return postSalesCase;
    }

    private static boolean requiresExclusiveRefundSlot(PostSalesCaseType caseType) {
        return caseType == PostSalesCaseType.REFUND
            || caseType == PostSalesCaseType.CANCELLATION
            || caseType == PostSalesCaseType.REBOOK
            || caseType == PostSalesCaseType.CHANGE;
    }

    private static boolean isTerminalNonRefundable(PostSalesCase postSalesCase) {
        return postSalesCase.status() == PostSalesCaseStatus.REJECTED
            || postSalesCase.status() == PostSalesCaseStatus.CANCELLED
            || postSalesCase.status() == PostSalesCaseStatus.FAILED;
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
        if (kind == DecisionKind.COMPENSATION) {
            AmountDecisionSnapshot amount = AmountDecisionSnapshot.refund(zero, refundable, explanation);
            return new PostSalesDecision(postSalesCase.caseId(), 1, kind, true, "ELIGIBLE", ruleSnapshot, amount, null, null, null, now, now.plusSeconds(900));
        }

        if (kind == DecisionKind.CHANGE) {
            Money originalFare = refundable.isZero() ? amountDue : refundable;
            Money newFare = amountDue.isZero() ? originalFare : originalFare.add(amountDue);
            ChangeAssessment assessment = changePolicyEngine.evaluateChange(originalFare, newFare, now, now.plusSeconds(60 * 60 * 24 * 20), 0);
            AmountDecisionSnapshot amount = assessment.netPayable().isZero() && !assessment.netRefundable().isZero()
                ? AmountDecisionSnapshot.refund(assessment.changeFee(), assessment.netRefundable(), assessment.explanation())
                : AmountDecisionSnapshot.extraCharge(assessment.changeFee(), assessment.netPayable(), assessment.explanation());
            ChangeFinancialAction financialAction = assessment.netPayable().isZero()
                ? (assessment.netRefundable().isZero() ? ChangeFinancialAction.NONE : ChangeFinancialAction.REQUEST_ASYNC_REFUND)
                : ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT;
            ChangeFlowSnapshot changeFlowSnapshot = new ChangeFlowSnapshot(
                "change-offer-" + postSalesCase.caseId(),
                "capacity-hold-" + postSalesCase.caseId(),
                financialAction,
                "void-old-entitlement-" + postSalesCase.caseId(),
                "issue-new-entitlement-" + postSalesCase.caseId(),
                "release-old-capacity-" + postSalesCase.caseId()
            );
            return PostSalesDecision.change(postSalesCase.caseId(), true, "ELIGIBLE", ruleSnapshot, amount, changeFlowSnapshot, assessment, now, now.plusSeconds(900));
        }

        Money penaltyBase = refundable.isZero() ? zero : refundable;
        RefundAssessment assessment = refundPolicyEngine.evaluateRefund(
            RefundWaterfall.simple(penaltyBase),
            penaltyBase,
            now,
            now.plusSeconds(60 * 60 * 24 * 20),
            classify(postSalesCase.reasonCode()),
            "ADULT",
            Math.max(1, postSalesCase.scope().travelerRefs().size())
        );
        AmountDecisionSnapshot amount = AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(), assessment.explanation(), assessment.componentDecisions());
        return PostSalesDecision.refund(postSalesCase.caseId(), true, "ELIGIBLE", ruleSnapshot, amount, assessment, now, now.plusSeconds(900));
    }

    private static RefundClassification classify(String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.toUpperCase(java.util.Locale.ROOT);
        if (reason.contains("CARRIER") || reason.contains("TRAIN_CANCEL") || reason.contains("TRAIN_CANCELLED")) {
            return RefundClassification.INVOLUNTARY_CARRIER;
        }
        if (reason.contains("DELAY")) {
            return RefundClassification.INVOLUNTARY_DELAY;
        }
        if (reason.contains("FORCE") || reason.contains("MAJEURE")) {
            return RefundClassification.INVOLUNTARY_FORCE_MAJEURE;
        }
        if (reason.contains("PLATFORM") || reason.contains("ERROR")) {
            return RefundClassification.INVOLUNTARY_PLATFORM;
        }
        return RefundClassification.VOLUNTARY;
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
