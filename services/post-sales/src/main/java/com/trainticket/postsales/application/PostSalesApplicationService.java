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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class PostSalesApplicationService {
    private final PostSalesRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;
    private final ConcurrentMap<String, Integer> publishedEventCounts = new ConcurrentHashMap<>();

    public PostSalesApplicationService(PostSalesRepository repository, EventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
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
        postSalesCase.recordDecision(decision, false, false, now, sourceCommandId, sourceCommandId, correlationId);
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
        RuleEvaluationSnapshot ruleSnapshot = new RuleEvaluationSnapshot(
            "adjq-" + postSalesCase.caseId(),
            "offer-rule-snapshot-" + postSalesCase.caseId(),
            "rule-v1",
            now,
            Map.of("journeyOrderId", postSalesCase.journeyOrderId(), "postSalesCaseId", postSalesCase.caseId())
        );
        Money zero = Money.zero("CNY");
        AmountDecisionSnapshot amount = switch (postSalesCase.caseType()) {
            case REFUND, CANCELLATION, REBOOK -> AmountDecisionSnapshot.refund(zero, Money.fromMinorUnits(0, "CNY"), "HTTP eligibility evaluation");
            case CHANGE -> AmountDecisionSnapshot.extraCharge(zero, Money.fromMinorUnits(0, "CNY"), "HTTP eligibility evaluation");
            case COMPENSATION -> AmountDecisionSnapshot.refund(zero, Money.fromMinorUnits(0, "CNY"), "HTTP eligibility evaluation");
        };
        DecisionKind kind = switch (postSalesCase.caseType()) {
            case REFUND, CANCELLATION, REBOOK -> DecisionKind.REFUND;
            case CHANGE -> DecisionKind.CHANGE;
            case COMPENSATION -> DecisionKind.COMPENSATION;
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

    private void publishNewEvents(PostSalesCase postSalesCase) {
        List<PostSalesEvent> events = postSalesCase.domainEvents();
        int alreadyPublished = publishedEventCounts.getOrDefault(postSalesCase.caseId(), 0);
        for (PostSalesEvent event : events.subList(alreadyPublished, events.size())) {
            eventPublisher.publish(PostSalesMapper.toEnvelope(event));
        }
        publishedEventCounts.put(postSalesCase.caseId(), events.size());
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
