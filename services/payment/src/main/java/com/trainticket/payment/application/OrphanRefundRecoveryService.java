package com.trainticket.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.adapters.paymentchannel.HttpPaymentChannelClient;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentIntentStatus;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.payment.domain.ports.RefundRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Service
public class OrphanRefundRecoveryService {
    private static final String RECOVERY_CAUSATION_ID = "cmd-orphan-refund-recovery";

    private final PaymentCommandService commands;
    private final RefundRepository refunds;
    private final HttpPaymentChannelClient channel;
    private final RestClient cases;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate withoutTransaction;

    public OrphanRefundRecoveryService(
        PaymentCommandService commands,
        RefundRepository refunds,
        HttpPaymentChannelClient channel,
        RestClient.Builder builder,
        ObjectMapper mapper,
        @Value("${POST_SALES_BASE_URL:http://post-sales:8080}") String base,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this.commands = Objects.requireNonNull(commands, "commands are required");
        this.refunds = Objects.requireNonNull(refunds, "refunds are required");
        this.channel = Objects.requireNonNull(channel, "channel is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        factory.setConnectTimeout(Duration.ofSeconds(2));
        this.cases = Objects.requireNonNull(builder, "builder is required")
            .requestFactory(factory)
            .baseUrl(Objects.requireNonNull(base, "post sales base URL is required"))
            .build();

        PlatformTransactionManager manager = transactionManager.orElse(null);
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
        if (this.transactions != null) {
            this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        this.withoutTransaction = manager == null ? null : new TransactionTemplate(manager);
        if (this.withoutTransaction != null) {
            this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        }
    }

    public Refund recover(String caseId, String channelRefundId) {
        String requiredCaseId = requireText(caseId, "caseId");
        String requiredChannelRefundId = requireText(channelRefundId, "channelRefundId");

        RecoveryEvidence evidence = outsideTransaction(() -> new RecoveryEvidence(
            approvedCase(requiredCaseId),
            channel.refundFacts(requiredChannelRefundId)
        ));
        String channelStatus = text(evidence.channelFacts(), "status");
        if (!List.of("CREATED", "SUCCEEDED").contains(channelStatus)) {
            throw new DomainRuleViolation("channel refund state requires separate reconciliation");
        }
        PreparedRecovery prepared = inTransaction(() -> prepare(requiredCaseId, requiredChannelRefundId, evidence));
        if (prepared.completed() != null) {
            return prepared.completed();
        }

        PaymentChannelClient.HandoffRefund handoff = switch (channelStatus) {
            case "SUCCEEDED" -> succeededHandoff(prepared, evidence.channelFacts());
            case "CREATED" -> outsideTransaction(() -> channel.resumeRefund(
                evidence.channelFacts(), prepared.submitKey(), prepared.correlationId(), prepared.route()));
            default -> throw new IllegalStateException("validated channel status changed");
        };
        return inTransaction(() -> apply(prepared, handoff));
    }

    private JsonNode approvedCase(String caseId) {
        return cases.get().uri("/api/v1/post-sales-cases/" + caseId).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("approved case unavailable");
            }
            return mapper.readTree(response.getBody());
        });
    }

    private PreparedRecovery prepare(String caseId, String channelRefundId, RecoveryEvidence evidence) {
        JsonNode approved = evidence.approvedCase();
        JsonNode facts = evidence.channelFacts();
        validateApproval(caseId, approved);
        if (!channelRefundId.equals(text(facts, "channelRefundId"))) {
            throw new DomainRuleViolation("channel refund identity mismatch");
        }

        String paymentId = text(facts, "paymentIntentId");
        String refundId = text(facts, "refundId");
        PaymentIntent intent = commands.getIntent(paymentId);
        ChannelRef route = intent.channelRef();
        Money amount = money(facts.path("amount"), "refund recovery requires integer amounts");
        Money approvedAmount = money(approved.path("decision").path("refundableAmount"), "refund recovery requires integer amounts");
        validatePaymentAndRoute(intent, route, approved, facts, amount, approvedAmount);

        List<Refund> caseRefunds = refunds.findByBusinessCaseRef(caseId);
        if (caseRefunds.stream().anyMatch(refund -> !refund.refundId().equals(refundId))) {
            throw new DomainRuleViolation("case already has another refund requiring reconciliation");
        }

        String reason = text(facts, "refundReasonCode");
        Refund recovered = refunds.findById(refundId).orElse(null);
        if (recovered != null && !recovered.semanticallyMatches(paymentId, amount, caseId, reason, caseId)) {
            throw new DomainRuleViolation("recorded refund belongs to another business operation");
        }
        if (recovered == null) {
            if (!intent.refundedAmount().isZero()) {
                throw new DomainRuleViolation("payment has previous refunds requiring reconciliation");
            }
            recovered = Refund.rehydrate(
                refundId, paymentId, amount, caseId, reason, caseId, RefundStatus.REQUESTED,
                null, 0, List.of(), route);
        }
        validateRecordedRoute(recovered, route, channelRefundId);

        String recoveryKey = recovered.channelRefundIdempotencyKey() == null
            ? recoveryKey(refundId, channelRefundId)
            : recovered.channelRefundIdempotencyKey();
        String submitKey = recovered.channelRefundSubmitIdempotencyKey() == null
            ? submitKey(refundId, channelRefundId)
            : recovered.channelRefundSubmitIdempotencyKey();
        if (recovered.status() == RefundStatus.SETTLED) {
            validateSettledReplay(recovered, facts, channelRefundId);
            return new PreparedRecovery(
                refundId, paymentId, amount, caseId, reason, route, channelRefundId, recoveryKey, submitKey,
                text(facts, "correlationId"), recovered);
        }

        boolean keysWereMissing = recovered.channelRefundIdempotencyKey() == null
            || recovered.channelRefundSubmitIdempotencyKey() == null;
        recovered.rememberChannelRefundKeys(recoveryKey, submitKey);
        if (recovered.version() == 0 || keysWereMissing) {
            refunds.save(recovered);
        }
        return new PreparedRecovery(
            refundId, paymentId, amount, caseId, reason, route, channelRefundId, recoveryKey, submitKey,
            text(facts, "correlationId"), null);
    }

    private Refund apply(PreparedRecovery prepared, PaymentChannelClient.HandoffRefund handoff) {
        validateHandoff(prepared, handoff);
        if ("SUCCEEDED".equals(handoff.status())) {
            return commands.settleRefundFromChannel(
                prepared.refundId(), prepared.paymentId(), prepared.amount(), prepared.channelRefundId(),
                prepared.route().channelOrderId(), prepared.route().channel(), prepared.route().channelTransactionId(),
                requireText(handoff.channelRefundTransactionId(), "channelRefundTransactionId"),
                RECOVERY_CAUSATION_ID, prepared.correlationId());
        }
        Refund current = refunds.findById(prepared.refundId())
            .orElseThrow(() -> new NotFoundException("refund not found"));
        validatePreparedIntent(current, prepared);

        if (current.status() == RefundStatus.SETTLED) {
            return current;
        }
        if (current.status() == RefundStatus.REQUESTED || current.status() == RefundStatus.FAILED) {
            current.recordChannelHandoff(handoff.channelRef());
            refunds.save(current);
            return current;
        }
        if (current.status() == RefundStatus.SUBMITTED) {
            validateRecordedRoute(current, prepared.route(), prepared.channelRefundId());
            return current;
        }
        throw new DomainRuleViolation("recorded refund state requires separate reconciliation");
    }

    private static PaymentChannelClient.HandoffRefund succeededHandoff(PreparedRecovery prepared, JsonNode facts) {
        String transactionId = text(facts, "channelRefundTransactionId");
        ChannelRef ref = prepared.route().withRefund(prepared.channelRefundId()).withRefundTransaction(transactionId);
        return new PaymentChannelClient.HandoffRefund(prepared.channelRefundId(), "SUCCEEDED", transactionId, ref);
    }

    private static void validateApproval(String caseId, JsonNode approved) {
        if (!caseId.equals(text(approved, "caseId"))
            || !"APPLIED".equals(text(approved, "status"))
            || !List.of("REFUND", "CANCELLATION").contains(text(approved, "caseType"))) {
            throw new DomainRuleViolation("applied refund case required");
        }
    }

    private static void validatePaymentAndRoute(
        PaymentIntent intent,
        ChannelRef route,
        JsonNode approved,
        JsonNode facts,
        Money amount,
        Money approvedAmount
    ) {
        if (!amount.equals(approvedAmount)
            || !intent.businessRef().equals(text(approved, "journeyOrderId"))
            || intent.status() != PaymentIntentStatus.CAPTURED
            || route == null
            || !Objects.equals(route.channelOrderId(), text(facts, "channelOrderId"))
            || !Objects.equals(route.channelTransactionId(), text(facts, "originalChannelTransactionId"))
            || !ChannelRouter.normalize(route.channel()).equals(ChannelRouter.normalize(text(facts, "channel")))) {
            throw new DomainRuleViolation("orphan refund does not match approved payment and original route");
        }
    }

    private static void validateRecordedRoute(Refund refund, ChannelRef route, String channelRefundId) {
        ChannelRef recorded = refund.channelRef();
        if (recorded == null
            || !ChannelRouter.normalize(route.channel()).equals(ChannelRouter.normalize(recorded.channel()))
            || !Objects.equals(route.channelOrderId(), recorded.channelOrderId())
            || !Objects.equals(route.channelTransactionId(), recorded.channelTransactionId())
            || (recorded.channelRefundId() != null && !recorded.channelRefundId().equals(channelRefundId))) {
            throw new DomainRuleViolation("recorded refund does not match the original payment route");
        }
    }

    private static void validateSettledReplay(Refund refund, JsonNode facts, String channelRefundId) {
        if (!"SUCCEEDED".equals(text(facts, "status"))
            || !Objects.equals(refund.channelRefundTransactionId(), text(facts, "channelRefundTransactionId"))
            || refund.channelRef() == null
            || !Objects.equals(refund.channelRef().channelRefundId(), channelRefundId)) {
            throw new DomainRuleViolation("settled refund does not match channel recovery facts");
        }
    }

    private static void validatePreparedIntent(Refund refund, PreparedRecovery prepared) {
        if (!refund.semanticallyMatches(
                prepared.paymentId(), prepared.amount(), prepared.caseId(), prepared.reason(), prepared.caseId())
            || !Objects.equals(refund.channelRefundIdempotencyKey(), prepared.refundKey())
            || !Objects.equals(refund.channelRefundSubmitIdempotencyKey(), prepared.submitKey())) {
            throw new DomainRuleViolation("refund recovery intent changed before the channel result was applied");
        }
        validateRecordedRoute(refund, prepared.route(), prepared.channelRefundId());
    }

    private static void validateHandoff(PreparedRecovery prepared, PaymentChannelClient.HandoffRefund handoff) {
        ChannelRef ref = Objects.requireNonNull(handoff.channelRef(), "channel handoff reference is required");
        if (!prepared.channelRefundId().equals(handoff.channelRefundId())
            || !prepared.channelRefundId().equals(ref.channelRefundId())
            || !ChannelRouter.normalize(prepared.route().channel()).equals(ChannelRouter.normalize(ref.channel()))
            || !Objects.equals(prepared.route().channelOrderId(), ref.channelOrderId())
            || !Objects.equals(prepared.route().channelTransactionId(), ref.channelTransactionId())
            || ("SUCCEEDED".equals(handoff.status())
                && !Objects.equals(handoff.channelRefundTransactionId(), ref.channelRefundTransactionId()))) {
            throw new DomainRuleViolation("channel recovery result does not match the persisted intent");
        }
    }

    private static Money money(JsonNode node, String error) {
        if (!node.path("minorUnits").isIntegralNumber()) {
            throw new DomainRuleViolation(error);
        }
        return Money.fromMinorUnits(node.path("minorUnits").asLong(), text(node, "currency"));
    }

    private static String recoveryKey(String refundId, String channelRefundId) {
        return Refund.stableId(refundId, channelRefundId, "recover-existing").substring(3);
    }

    private static String submitKey(String refundId, String channelRefundId) {
        return Refund.stableId(refundId, channelRefundId, "recover-submit").substring(3);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(status -> work.get());
    }

    private <T> T outsideTransaction(Supplier<T> work) {
        return withoutTransaction == null ? work.get() : withoutTransaction.execute(status -> work.get());
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new DomainRuleViolation("recovery facts missing " + field);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolation(field + " must not be blank");
        }
        return value;
    }

    private record RecoveryEvidence(JsonNode approvedCase, JsonNode channelFacts) {
    }

    private record PreparedRecovery(
        String refundId,
        String paymentId,
        Money amount,
        String caseId,
        String reason,
        ChannelRef route,
        String channelRefundId,
        String refundKey,
        String submitKey,
        String correlationId,
        Refund completed
    ) {
    }
}
