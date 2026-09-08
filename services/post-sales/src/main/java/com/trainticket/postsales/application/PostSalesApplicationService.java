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
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.postsales.domain.RuleEvaluationSnapshot;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PostSalesApplicationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostSalesApplicationService.class);

    private final PostSalesRepository repository;
    private final EventPublisher eventPublisher;
    private final AdjustmentQuotePort adjustmentQuotePort;
    private final PostSalesPolicyContextStore policyContextStore;
    private final PostSalesExternalEventProjectionStore externalEventProjectionStore;
    private final Clock clock;
    private final RefundPolicyEngine refundPolicyEngine = new RefundPolicyEngine();
    private final ChangePolicyEngine changePolicyEngine = new ChangePolicyEngine();

    public PostSalesApplicationService(PostSalesRepository repository, EventPublisher eventPublisher,
            AdjustmentQuotePort adjustmentQuotePort, PostSalesPolicyContextStore policyContextStore, Clock clock) {
        this(repository, eventPublisher, adjustmentQuotePort, policyContextStore, new InMemoryPostSalesExternalEventProjectionStore(), clock);
    }

    @Autowired
    public PostSalesApplicationService(PostSalesRepository repository, EventPublisher eventPublisher,
            AdjustmentQuotePort adjustmentQuotePort, PostSalesPolicyContextStore policyContextStore,
            PostSalesExternalEventProjectionStore externalEventProjectionStore, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.adjustmentQuotePort = adjustmentQuotePort;
        this.policyContextStore = policyContextStore;
        this.externalEventProjectionStore = externalEventProjectionStore;
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
        if (postSalesCase.caseType() == PostSalesCaseType.CHANGE) {
            policyContextStore.findByOrderId(postSalesCase.journeyOrderId())
                .map(PostSalesPolicyContext::incrementAppliedChangeCount)
                .ifPresent(policyContextStore::save);
        }
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

        // REFUND is the one decision kind where a fallback context produces a WRONG
        // answer rather than a harmless one: departureTime=now forces
        // AFTER_DEPARTURE_NON_REFUNDABLE, a 100% penalty and a zero refund, which
        // payment then skips without publishing anything. CHANGE tolerates it.
        PostSalesPolicyContext policyContext =
            policyContextFor(postSalesCase, now, refundable, kind == DecisionKind.REFUND);
        if (kind == DecisionKind.CHANGE) {
            Money originalFare = policyContext.originalFareOr(amountDue.isZero() ? refundable : amountDue);
            ChangeAssessment assessment = changePolicyEngine.evaluateQuotedChange(
                originalFare,
                amountDue,
                refundable,
                now,
                policyContext.departureTime(),
                policyContext.appliedChangeCount()
            );
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

        Money penaltyBase = policyContext.originalFareOr(refundable.isZero() ? zero : refundable);
        RefundAssessment assessment = refundPolicyEngine.evaluateRefund(
            policyContext.waterfallFor(penaltyBase),
            penaltyBase,
            now,
            policyContext.departureTime(),
            classify(postSalesCase.reasonCode()),
            policyContext.travelerType(postSalesCase.scope().travelerRefs()),
            policyContext.groupSize()
        );
        // The whole pricing decision on one line, keyed by case and order. Every
        // input that can change the outcome is here, so a wrong refund can be
        // diagnosed from logs alone instead of by reconstructing state from the
        // snapshot tables afterwards. INFO because a refund quote is a
        // business-significant decision, not debug detail.
        LOGGER.info("post-sales refund priced case={} order={} tier={} classification={} "
                + "penaltyBase={} penalty={} refundable={} departureTime={} requestTime={} "
                + "travelerType={} groupSize={}",
            postSalesCase.caseId(), postSalesCase.journeyOrderId(),
            assessment.tierApplied(), assessment.classification(),
            penaltyBase, assessment.penaltyAmount(), assessment.refundableAmount(),
            policyContext.departureTime(), now,
            policyContext.travelerType(postSalesCase.scope().travelerRefs()),
            policyContext.groupSize());
        AmountDecisionSnapshot amount = AmountDecisionSnapshot.refund(assessment.penaltyAmount(), assessment.refundableAmount(), assessment.explanation(), assessment.componentDecisions());
        return PostSalesDecision.refund(postSalesCase.caseId(), true, "ELIGIBLE", ruleSnapshot, amount, assessment, now, now.plusSeconds(900));
    }

    private PostSalesPolicyContext policyContextFor(
            PostSalesCase postSalesCase, Instant now, Money fallbackAmount, boolean requireContext) {
        Optional<PostSalesPolicyContext> stored = policyContextStore.findByOrderId(postSalesCase.journeyOrderId());
        if (stored.isPresent()) {
            PostSalesPolicyContext context = stored.get();
            LOGGER.debug("post-sales policy context HIT case={} order={} departureTime={} originalFare={} groupSize={}",
                postSalesCase.caseId(), postSalesCase.journeyOrderId(),
                context.departureTime(), context.originalFareOr(fallbackAmount), context.groupSize());
            return context;
        }
        // WARN, not debug: this is the single most consequential silent branch in
        // the service. The fallback sets departureTime to the REQUEST time, so
        // RefundPolicyEngine reads beforeDeparture == 0 and returns
        // AFTER_DEPARTURE_NON_REFUNDABLE -- a 100% penalty and a zero refund, for
        // a journey that may be a month away. Downstream, payment's
        // handlePostSalesApproved returns early on the zero amount, so no
        // RefundRequested is ever published and the customer is simply not
        // refunded. Every step of that is currently invisible; this line is what
        // makes it attributable to a missing policy context rather than to a
        // pricing rule.
        if (requireContext) {
            // Refuse rather than price it wrong. See PolicyContextUnavailableException:
            // the context arrives from journey-order over a stream, so a refund request
            // can outrun it, and on the live cluster every order that missed had its
            // context within seconds. A retryable 409 is recoverable; a zero refund the
            // caller cannot distinguish from a correct one is not.
            LOGGER.warn("post-sales policy context MISS case={} order={} -- refusing to price a "
                    + "refund without it. The context is written from "
                    + "JourneyOrderCreated/Confirmed; if this persists for one order, that event "
                    + "carried no segments[].departureTime or was never consumed.",
                postSalesCase.caseId(), postSalesCase.journeyOrderId());
            throw new PolicyContextUnavailableException(postSalesCase.journeyOrderId());
        }
        LOGGER.warn("post-sales policy context MISS case={} order={} -- falling back to "
                + "departureTime=now. Harmless for this decision kind, but it would force "
                + "AFTER_DEPARTURE_NON_REFUNDABLE on a refund.",
            postSalesCase.caseId(), postSalesCase.journeyOrderId());
        return PostSalesPolicyContext.fallback(
            postSalesCase.journeyOrderId(),
            now,
            postSalesCase.scope().travelerRefs().size(),
            fallbackAmount
        );
    }

    public void recordPolicyContext(PostSalesPolicyContext context) {
        policyContextStore.save(context);
    }

    public void recordAncillaryProjection(AncillaryPostSalesProjection projection) {
        externalEventProjectionStore.saveAncillary(projection);
        policyContextStore.findByOrderId(projection.journeyOrderId())
            .map(context -> context.withAncillaryComponent(
                money(projection.refundableAmount()),
                "FULFILLED".equals(projection.status())
            ))
            .ifPresent(policyContextStore::save);
    }

    public void recordDispatchProjection(DispatchPostSalesProjection projection) {
        externalEventProjectionStore.saveDispatch(projection);
    }

    public PostSalesCase openCompensationForAncillaryFailure(AncillaryPostSalesProjection projection) {
        String idempotencyKey = "ancillary-service:" + projection.lastEventId() + ":compensation";
        return open(new OpenCaseCommand(
            projection.journeyOrderId(),
            PostSalesCaseType.COMPENSATION,
            scopeForAncillary(projection),
            reasonOrDefault(projection.failureCode(), "ANCILLARY_FAILURE"),
            "ancillary-service",
            idempotencyKey,
            "cmd-" + projection.lastEventId(),
            projection.lastEventId()
        ));
    }

    public PostSalesCase linkRefundForAncillaryCancellation(AncillaryPostSalesProjection projection) {
        String idempotencyKey = "ancillary-service:" + projection.lastEventId() + ":refund";
        return repository.findByIdempotencyKey(idempotencyKey)
            .or(() -> existingActiveRefundCaseForOrder(projection.journeyOrderId()))
            .orElseGet(() -> open(new OpenCaseCommand(
                projection.journeyOrderId(),
                PostSalesCaseType.REFUND,
                scopeForAncillary(projection),
                reasonOrDefault(projection.reasonCode(), "ANCILLARY_CANCELLED"),
                "ancillary-service",
                idempotencyKey,
                "cmd-" + projection.lastEventId(),
                projection.lastEventId()
            )));
    }

    private Optional<PostSalesCase> existingActiveRefundCaseForOrder(String journeyOrderId) {
        return repository.findActiveRefundCaseForOrder(journeyOrderId);
    }

    private static PostSalesScope scopeForAncillary(AncillaryPostSalesProjection projection) {
        return new PostSalesScope(
            List.of(projection.ancillaryOrderItemId()),
            optionalList(projection.segmentRef()),
            List.of(projection.travelerRef()),
            List.of()
        );
    }

    private static List<String> optionalList(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private static Money money(ExternalMoney externalMoney) {
        return externalMoney == null ? null : Money.fromMinorUnits(externalMoney.minorUnits(), externalMoney.currency());
    }

    private static String reasonOrDefault(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
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
