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
        if (requiresExclusiveRefundSlot(postSalesCase.caseType())) rejectAlreadyAppliedScope(postSalesCase.journeyOrderId(), postSalesCase.scope(), postSalesCase.caseId());
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

    public PostSalesCase selectChangeOffer(String caseId, String offerId) {
        PostSalesCase initial = get(caseId);
        repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current = get(caseId);
        current.selectChangeOffer(offerId);
        repository.save(current);
        return current;
    }

    public PostSalesCase recordChangeOperation(String caseId, String name, Map<String,Object> result) {
        PostSalesCase initial=get(caseId);
        repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current=get(caseId);
        current.recordChangeOperation(name,result);
        repository.save(current);
        return current;
    }

    public PostSalesCase reviseChangeOffer(String caseId,String expected,String replacement) {
        PostSalesCase initial=get(caseId);repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current=get(caseId);current.reviseChangeOffer(expected,replacement);repository.save(current);return current;
    }

    public List<PostSalesCase> appliedChanges(String orderId){return repository.findAppliedCasesForOrder(orderId).stream().filter(c->c.caseType()==PostSalesCaseType.CHANGE && c.changeOperations().containsKey("target")).toList();}

    public PostSalesCase assignRefundPayments(String caseId,List<Map<String,Object>> payments){
        PostSalesCase initial=get(caseId);repository.lockOrder(initial.journeyOrderId());PostSalesCase current=get(caseId);
        current.assignRefundPayments(payments);repository.save(current);return current;
    }

    public PostSalesCase completeChange(String caseId,String correlation) {
        PostSalesCase initial=get(caseId);
        repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current=get(caseId);
        if(current.caseType()!=PostSalesCaseType.CHANGE) throw new com.trainticket.postsales.domain.DomainRuleViolation("only CHANGE execution can complete here");
        if(current.status()==PostSalesCaseStatus.APPLIED)return current;
        if(!current.changeOperations().containsKey("order-applied"))throw new com.trainticket.postsales.domain.DomainRuleViolation("replacement order update has not completed");
        if(!current.releasedOrderItems().containsAll(current.scope().orderItemRefs()))throw new com.trainticket.postsales.domain.DomainRuleViolation("original capacity release has not completed");
        current.applyResult("replacement tickets and financial adjustment completed",clock.instant(),"cmd-change-applied","cmd-change-applied",correlation);
        repository.save(current);
        policyContextStore.findByOrderId(current.journeyOrderId()).map(PostSalesPolicyContext::incrementAppliedChangeCount).ifPresent(policyContextStore::save);
        publishNewEvents(current);
        return current;
    }

    public PostSalesCase recordChangeCapacityReleased(String caseId, String bookingId) {
        PostSalesCase initial = get(caseId);
        repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current = get(caseId);
        if (current.caseType() == PostSalesCaseType.CHANGE && current.releasedOrderItems().contains(bookingId)) return current;
        if (current.caseType() != PostSalesCaseType.CHANGE || current.status() != PostSalesCaseStatus.EXECUTING) {
            throw new com.trainticket.postsales.domain.DomainRuleViolation("capacity confirmation requires an executing change");
        }
        current.recordCapacityReleased(bookingId);
        repository.save(current);
        return current;
    }

    public PostSalesCase failUnpaidChange(String caseId,String correlation) {
        PostSalesCase initial=get(caseId);
        repository.lockOrder(initial.journeyOrderId());
        PostSalesCase current=get(caseId);
        if(current.status()==PostSalesCaseStatus.FAILED)return current;
        if(current.caseType()!=PostSalesCaseType.CHANGE || !current.changeOperations().containsKey("unpaid-compensation-completed")
            || current.changeOperations().keySet().stream().anyMatch(name->name.startsWith("ticket:") || name.startsWith("void:") || name.equals("order-applied"))) {
            throw new com.trainticket.postsales.domain.DomainRuleViolation("unpaid change compensation has not completed");
        }
        current.failCase("CHANGE_PAYMENT_TERMINATED","difference-payment",clock.instant(),"cmd-change-failed","cmd-change-failed",correlation);
        repository.save(current);
        publishNewEvents(current);
        return current;
    }

    public PostSalesCase approve(String caseId, String sourceCommandId, String correlationId) {
        PostSalesCase postSalesCase = get(caseId);
        if (isTerminalNonRefundable(postSalesCase)) {
            throw new OrderNotRefundableException();
        }
        // Approval is convergent: a case already executing or applied is done, and a case
        // left in APPROVED by an earlier call still needs to be pushed into EXECUTING.
        // Previously APPROVED returned immediately without starting execution, so the
        // case never emitted PostSalesExecutionStarted and the choreography stalled.
        if (postSalesCase.status() == PostSalesCaseStatus.EXECUTING || postSalesCase.status() == PostSalesCaseStatus.APPLIED) {
            return postSalesCase;
        }
        if (requiresExclusiveRefundSlot(postSalesCase.caseType())) rejectAlreadyAppliedScope(postSalesCase.journeyOrderId(), postSalesCase.scope(), postSalesCase.caseId());
        if (postSalesCase.status() == PostSalesCaseStatus.APPROVED) {
            startApprovedExecution(postSalesCase, sourceCommandId, correlationId);
            return postSalesCase;
        }
        if (postSalesCase.decision() == null) {
            evaluate(caseId, sourceCommandId, correlationId);
            postSalesCase = get(caseId);
        }
        postSalesCase.approve("http-approval", clock.instant(), sourceCommandId, sourceCommandId, correlationId);
        startApprovedExecution(postSalesCase, sourceCommandId, correlationId);
        return postSalesCase;
    }

    private void startApprovedExecution(PostSalesCase postSalesCase, String sourceCommandId, String correlationId) {
        postSalesCase.startExecution(clock.instant(), sourceCommandId, sourceCommandId, correlationId);
        repository.save(postSalesCase);
        publishNewEvents(postSalesCase);
    }

    public PostSalesCase get(String caseId) {
        return repository.findById(PostSalesMapper.stripCasePrefix(caseId))
            .orElseThrow(() -> new CaseNotFoundException(caseId));
    }


    private PostSalesCase openNewCase(OpenCaseCommand command) {
        repository.lockOrder(command.journeyOrderId());
        if (requiresExclusiveRefundSlot(command.caseType())) {
            rejectAlreadyAppliedScope(command.journeyOrderId(), command.scope(), null);
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

    private void rejectAlreadyAppliedScope(String orderId, PostSalesScope scope, String currentCaseId) {
        for (PostSalesCase applied : repository.findAppliedCasesForOrder(orderId)) {
            if (applied.caseId().equals(currentCaseId) || !requiresExclusiveRefundSlot(applied.caseType())) continue;
            if (applied.scope().entitlementRefs().stream().anyMatch(scope.entitlementRefs()::contains)
                || applied.scope().orderItemRefs().stream().anyMatch(scope.orderItemRefs()::contains)) {
                throw new OrderNotRefundableException();
            }
        }
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
            PostSalesMapper.stripCasePrefix(postSalesCase.caseId()),
            postSalesCase.targetOfferId()
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

        PostSalesPolicyContext policyContext = policyContextFor(postSalesCase);
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
                postSalesCase.targetOfferId()==null?"change-offer-" + postSalesCase.caseId():postSalesCase.targetOfferId(),
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

    PostSalesPolicyContext policyContextFor(PostSalesCase postSalesCase) {
        var changes=appliedChanges(postSalesCase.journeyOrderId());
        var latest=changes.stream().max(java.util.Comparator.comparing(PostSalesCase::createdAt));
        if(latest.isPresent()){
            var target=latest.get().changeOperations().get("target");
            var offer=(Map<?,?>)target.get("offer");var itinerary=(Map<?,?>)target.get("itinerary");
            var legs=(List<?>)itinerary.get("legs");var first=(Map<?,?>)legs.getFirst();
            var total=(Map<?,?>)offer.get("total");Money fare=Money.fromMinorUnits(((Number)total.get("minorUnits")).longValue(),String.valueOf(total.get("currency")));
            Map<String,String> types=new java.util.LinkedHashMap<>();var mix=(Map<?,?>)offer.get("passengerMix");
            for(Object raw:(List<?>)mix.get("travelers")){var traveler=(Map<?,?>)raw;types.put(String.valueOf(traveler.get("travelerId")),String.valueOf(traveler.get("travelerType")));}
            return new PostSalesPolicyContext(postSalesCase.journeyOrderId(),Instant.parse(String.valueOf(first.get("departureTime"))),types,types.size(),changes.size(),fare,PostSalesPolicyContext.RefundWaterfallComponents.empty(fare.currency()));
        }
        Optional<PostSalesPolicyContext> stored = policyContextStore.findByOrderId(postSalesCase.journeyOrderId());
        if (stored.isPresent()) {
            PostSalesPolicyContext context = stored.get();
            LOGGER.debug("post-sales policy context HIT case={} order={} departureTime={} originalFare={} groupSize={}",
                postSalesCase.caseId(), postSalesCase.journeyOrderId(),
                context.departureTime(), context.originalFare(), context.groupSize());
            return context;
        }
        LOGGER.warn("post-sales pricing requires order context case={} order={}",
            postSalesCase.caseId(), postSalesCase.journeyOrderId());
        throw new PolicyContextUnavailableException(postSalesCase.journeyOrderId());
    }

    /**
     * Updates an existing context's fare from an event that carries a monetary
     * summary but no itinerary.
     *
     * A no-op when no context exists: without a departure time there is nothing
     * to attach a fare to, and only JourneyOrderCreated establishes that. Doing
     * nothing is correct here -- the alternative, rebuilding from a payload with
     * no segments, is what made JourneyOrderConfirmed and
     * JourneyOrderPostSalesAdjusted log a warning on every single order claiming
     * refunds would quote zero, when JourneyOrderCreated had already stored a
     * usable context.
     */
    public void refreshPolicyContextFare(String journeyOrderId, Map<?, ?> payload) {
        Optional<PostSalesPolicyContext> existing = policyContextStore.findByOrderId(journeyOrderId);
        if (existing.isEmpty()) {
            LOGGER.debug("no policy context yet for order {}; nothing to refresh", journeyOrderId);
            return;
        }
        PostSalesPolicyContextMapper.fareUpdateFromEventPayload(payload).ifPresentOrElse(
            update -> {
                policyContextStore.save(existing.get().withFare(update.originalFare(), update.components()));
                LOGGER.debug("refreshed policy context fare for order {} to {}",
                    journeyOrderId, update.originalFare());
            },
            () -> LOGGER.debug("event for order {} carried no usable amount; fare left unchanged", journeyOrderId));
    }

    public void recordPolicyContext(PostSalesPolicyContext context) {
        policyContextStore.save(context);
    }

    public void recordAncillaryProjection(AncillaryPostSalesProjection projection) {
        externalEventProjectionStore.saveAncillary(projection);
        if(projection.paymentIntentId()!=null) return;
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

    /** Persist every scoped reservation release before completing capacity execution. */
    public void applyForSegmentBooking(String segmentBookingRef, String causationId, String correlationId) {
        for (PostSalesCase postSalesCase : repository.findByOrderItemRef(segmentBookingRef)) {
            java.time.Instant now = java.time.Instant.now(clock);
            if (postSalesCase.status() == com.trainticket.postsales.domain.PostSalesCaseStatus.APPROVED) {
                postSalesCase.startExecution(now, "cmd-capacity-released", causationId, correlationId);
            }
            if (postSalesCase.status() == com.trainticket.postsales.domain.PostSalesCaseStatus.EXECUTING) {
                if (postSalesCase.recordCapacityReleased(segmentBookingRef) && postSalesCase.caseType()!=PostSalesCaseType.CHANGE) {
                    postSalesCase.applyResult("all scoped capacity released", now, "cmd-capacity-released", causationId, correlationId);
                }
                repository.save(postSalesCase);
                publishNewEvents(postSalesCase);
            } else if (postSalesCase.caseType() == PostSalesCaseType.CHANGE
                && postSalesCase.status() == PostSalesCaseStatus.APPLIED
                && !postSalesCase.releasedOrderItems().contains(segmentBookingRef)) {
                postSalesCase.recordCapacityReleased(segmentBookingRef);
                repository.save(postSalesCase);
            }
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
