package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.LatePaymentCase;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentEvent;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.payment.domain.ports.LatePaymentCaseRepository;
import com.trainticket.payment.domain.ports.RefundRepository;
import com.trainticket.payment.domain.ports.ReservationPaymentRequestRepository;
import com.trainticket.payment.observability.NoopPaymentMetrics;
import com.trainticket.payment.observability.PaymentMetricLabels;
import com.trainticket.payment.observability.PaymentMetricRecorder;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentCommandService {
    private static final String DEFAULT_CHANNEL = System.getenv().getOrDefault("PAYMENT_DEFAULT_CHANNEL", "ALIPAY_SIM");

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final ReservationPaymentRequestRepository reservationPaymentRequestRepository;
    private final LatePaymentCaseRepository latePaymentCaseRepository;
    private final PaymentChannelClient paymentChannelClient;
    private final ChannelRouter channelRouter;
    private final TransactionTemplate transactionTemplate;
    private PaymentMetricRecorder metrics = NoopPaymentMetrics.INSTANCE;

    /**
     * Bound by Spring once the observability configuration exists. Tests that
     * construct the service directly keep the no-op recorder.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaymentMetricRecorder(PaymentMetricRecorder recorder) {
        if (recorder != null) {
            this.metrics = recorder;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        LatePaymentCaseRepository latePaymentCaseRepository,
        PaymentChannelClient paymentChannelClient,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, latePaymentCaseRepository, paymentChannelClient, ChannelRouter.defaults(), transactionManager);
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        LatePaymentCaseRepository latePaymentCaseRepository,
        PaymentChannelClient paymentChannelClient,
        ChannelRouter channelRouter,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.paymentIntentRepository = Objects.requireNonNull(paymentIntentRepository, "paymentIntentRepository is required");
        this.refundRepository = Objects.requireNonNull(refundRepository, "refundRepository is required");
        this.reservationPaymentRequestRepository = Objects.requireNonNull(reservationPaymentRequestRepository, "reservationPaymentRequestRepository is required");
        this.latePaymentCaseRepository = Objects.requireNonNull(latePaymentCaseRepository, "latePaymentCaseRepository is required");
        this.paymentChannelClient = Objects.requireNonNull(paymentChannelClient, "paymentChannelClient is required");
        this.channelRouter = Objects.requireNonNull(channelRouter, "channelRouter is required");
        this.transactionTemplate = transactionManager.map(TransactionTemplate::new).orElse(null);
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        LatePaymentCaseRepository latePaymentCaseRepository,
        PaymentChannelClient paymentChannelClient,
        ChannelRouter channelRouter
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, latePaymentCaseRepository, paymentChannelClient, channelRouter, Optional.empty());
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        LatePaymentCaseRepository latePaymentCaseRepository,
        PaymentChannelClient paymentChannelClient
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, latePaymentCaseRepository, paymentChannelClient, ChannelRouter.defaults());
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        LatePaymentCaseRepository latePaymentCaseRepository
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, latePaymentCaseRepository, new NoopPaymentChannelClient());
    }

    @Transactional
    public ReservationPaymentRequest recordReservationPaymentRequest(
        String eventId,
        String segmentBookingId,
        String journeyOrderId,
        String segmentRef,
        String travelerRef,
        String idempotencyKey,
        String correlationId,
        Instant requestedAt
    ) {
        ReservationPaymentRequest request = new ReservationPaymentRequest(
            eventId,
            segmentBookingId,
            journeyOrderId,
            segmentRef,
            travelerRef,
            idempotencyKey,
            correlationId,
            requestedAt
        );
        return reservationPaymentRequestRepository.saveIfAbsent(request);
    }

    @Transactional
    public PaymentIntent createIntent(String businessRef, String purpose, Money amount, String payerRef, String idempotencyKey, String correlationId) {
        return createIntent(businessRef, purpose, amount, payerRef, idempotencyKey, correlationId, null);
    }

    @Transactional
    public PaymentIntent createIntent(String businessRef, String purpose, Money amount, String payerRef, String idempotencyKey, String correlationId, String preferredChannel) {
        var existing = paymentIntentRepository.findByIdempotencyKey(requireText(idempotencyKey, "idempotencyKey"));
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            if (!intent.businessRef().equals(businessRef) || !intent.purpose().equals(purpose)
                || !intent.amount().equals(amount) || !intent.payerRef().equals(payerRef)
                || (preferredChannel != null && !preferredChannel.isBlank()
                    && !ChannelRouter.normalize(preferredChannel).equals(ChannelRouter.normalize(intent.channelRef().channel())))) {
                throw new DomainRuleViolation("payment creation key was reused with different request details");
            }
            return intent;
        }
        Instant now = Instant.now(clock);
        PaymentChannel channel = channelRouter.route(amount, preferredChannel);
        PaymentIntent intent = PaymentIntent.create(businessRef, purpose, amount, payerRef, now.plusSeconds(channel.timeoutSeconds()), idempotencyKey, now, commandId(idempotencyKey), correlationId);
        intent.recordChannelHandoff(new ChannelRef(channel.channelId(), null, null, null, null, null, null));
        paymentIntentRepository.save(intent);
        publish(intent.domainEvents());
        return intent;
    }

    @Transactional
    public PaymentIntent cancelIntent(String paymentIntentId, String reason, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.cancel(reason, Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId) {
        return captureIntent(paymentIntentId, idempotencyKey, correlationId, (String) null);
    }

    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId, String requestedChannel) {
        PreparedCapture prepared = inTransaction(() -> {
            PaymentIntent intent = getIntent(paymentIntentId);
            String channel = requestedChannel == null || requestedChannel.isBlank()
                ? (intent.channelRef() == null || intent.channelRef().channel() == null ? DEFAULT_CHANNEL : intent.channelRef().channel())
                : requestedChannel;
            ChannelRef requestedRef = new ChannelRef(channelRouter.requireAvailable(channel, intent.amount()).channelId(), null, null, null, null, null, null);
            return prepareCaptureSubmission(intent, requestedRef, idempotencyKey);
        });
        if (prepared.replay()) return prepared.intent();
        PaymentChannelClient.HandoffOrder handoff = paymentChannelClient.handoffCapture(prepared.intent(), prepared.orderKey(), prepared.submitKey(), correlationId, prepared.requestedRef());
        return inTransaction(() -> applyRemainingCaptureHandoff(paymentIntentId, idempotencyKey, correlationId, prepared, handoff));
    }

    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId, ChannelRef requestedRef) {
        if (requestedRef == null) {
            return captureIntent(paymentIntentId, idempotencyKey, correlationId, (String) null);
        }
        PreparedCapture prepared = inTransaction(() -> {
            PaymentIntent intent = getIntent(paymentIntentId);
            ChannelRef effectiveRef = new ChannelRef(
                channelRouter.requireAvailable(requestedRef.channel(), intent.amount()).channelId(),
                requestedRef.channelOrderId(), requestedRef.channelRefundId(), requestedRef.channelTransactionId(),
                requestedRef.channelRefundTransactionId(), requestedRef.channelStatementId(), requestedRef.faultSeedRef());
            return prepareCaptureSubmission(intent, effectiveRef, idempotencyKey);
        });
        if (prepared.replay()) return prepared.intent();
        PaymentChannelClient.HandoffOrder handoff = paymentChannelClient.handoffCapture(prepared.intent(), prepared.orderKey(), prepared.submitKey(), correlationId, prepared.requestedRef());
        return inTransaction(() -> applyChannelRouteCaptureHandoff(paymentIntentId, idempotencyKey, correlationId, prepared, handoff));
    }

    private PreparedCapture prepareCaptureSubmission(PaymentIntent intent, ChannelRef requestedRef, String idempotencyKey) {
        if (isCapturedReplay(intent, requestedRef)) {
            return new PreparedCapture(intent, requestedRef, null, null, true);
        }
        String orderKey = keyOrFold(intent.channelOrderIdempotencyKey(), intent.paymentIntentId() + ":1", idempotencyKey);
        String submitKey = keyOrFold(intent.channelOrderSubmitIdempotencyKey(), intent.paymentIntentId() + ":1:submit", idempotencyKey);
        // The channel sees the keys that are already committed, so a retry or a
        // concurrent attempt reuses the same channel order and submit keys.
        intent.rememberChannelOrderKeys(orderKey, submitKey);
        paymentIntentRepository.save(intent);
        return new PreparedCapture(intent, requestedRef, orderKey, submitKey, false);
    }

    private PaymentIntent applyRemainingCaptureHandoff(
        String paymentIntentId,
        String idempotencyKey,
        String correlationId,
        PreparedCapture prepared,
        PaymentChannelClient.HandoffOrder handoff
    ) {
        PaymentIntent intent = getIntent(paymentIntentId);
        if (isCapturedReplay(intent, prepared.requestedRef())) return intent;
        intent.recordChannelHandoff(handoff.channelRef());
        if ("SUCCEEDED".equals(handoff.status()) && handoff.channelTransactionId() != null) {
            int before = intent.domainEvents().size();
            Money captureAmount = intent.amount().subtract(intent.capturedAmount());
            intent.capture(captureAmount, prepared.requestedRef().channel(), handoff.channelTransactionId(), Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
            paymentIntentRepository.save(intent);
            publishNewEvents(intent.domainEvents(), before);
            recordMoney(PaymentMetricLabels.Operation.CAPTURE, PaymentMetricLabels.Outcome.SUCCEEDED, captureAmount, prepared.requestedRef().channel());
        } else {
            paymentIntentRepository.save(intent);
        }
        return intent;
    }

    private PaymentIntent applyChannelRouteCaptureHandoff(
        String paymentIntentId,
        String idempotencyKey,
        String correlationId,
        PreparedCapture prepared,
        PaymentChannelClient.HandoffOrder handoff
    ) {
        PaymentIntent intent = getIntent(paymentIntentId);
        if (isCapturedReplay(intent, prepared.requestedRef())) return intent;
        intent.recordChannelHandoff(handoff.channelRef());
        paymentIntentRepository.save(intent);
        if ("SUCCEEDED".equals(handoff.status())) {
            return applyChannelCapture(paymentIntentId, intent.amount(), handoff.channelRef().channel(),
                handoff.channelTransactionId(), handoff.channelOrderId(), commandId(idempotencyKey), correlationId);
        }
        return intent;
    }

    @Transactional
    public PaymentIntent authorizeIntent(String paymentIntentId, String idempotencyKey, String correlationId, String channelTransactionRef) {
        PaymentIntent intent = getIntent(paymentIntentId);
        String channel = intent.channelRef() == null || intent.channelRef().channel() == null ? DEFAULT_CHANNEL : intent.channelRef().channel();
        channel = channelRouter.requireAvailable(channel, intent.amount()).channelId();
        int before = intent.domainEvents().size();
        intent.authorize(intent.amount(), channel, "auth-" + requireText(channelTransactionRef, "channelTransactionRef"), Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    private boolean isCapturedReplay(PaymentIntent intent, ChannelRef requested) {
        if (intent.status() != com.trainticket.payment.domain.PaymentIntentStatus.CAPTURED) return false;
        ChannelRef original = intent.channelRef();
        if (original == null || !ChannelRouter.normalize(original.channel()).equals(ChannelRouter.normalize(requested.channel()))
            || (requested.channelOrderId() != null && !requested.channelOrderId().equals(original.channelOrderId()))
            || (requested.channelTransactionId() != null && !requested.channelTransactionId().equals(original.channelTransactionId()))) {
            throw new DomainRuleViolation("captured payment replay must use the original channel route");
        }
        return true;
    }

    // A submitted channel order is identified by the keys persisted in the
    // first transaction; a replay of an already captured intent skips the call.
    private record PreparedCapture(PaymentIntent intent, ChannelRef requestedRef, String orderKey, String submitKey, boolean replay) {
    }

    // The refund and its channel keys are committed before the channel sees them.
    // A null refundKey means the request was settled without a channel call, so
    // the caller returns the refund instead of handing it to the channel.
    record RefundHandoff(PaymentIntent intent, Refund refund, ChannelRef originalRoute, String refundKey, String submitKey) {
        boolean needsChannel() {
            return refundKey != null;
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    /** Latest captured (or created) intent for a business reference, used
     * when post-sales approvals reference the order rather than the intent. */
    public java.util.Optional<String> findIntentIdByBusinessRef(String businessRef) {
        return paymentIntentRepository.findLatestByBusinessRef(businessRef).map(PaymentIntent::paymentIntentId);
    }

    @Transactional
    public Refund requestRefund(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        String caseRef = businessCaseRef == null || businessCaseRef.isBlank() ? "case-" + idempotencyKey : businessCaseRef;
        for (Refund existing : refundRepository.findByBusinessCaseRef(caseRef)) {
            if (existing.idempotencyKey().equals(idempotencyKey)) {
                if (!existing.semanticallyMatches(paymentIntentId, amount, caseRef, reason, idempotencyKey)) {
                    throw new DomainRuleViolation("refund idempotency key was reused with different request details");
                }
                return existing;
            }
        }
        Refund refund = Refund.request(intent, amount, caseRef, reason, idempotencyKey, Instant.now(clock), commandId(idempotencyKey), correlationId);
        refundRepository.save(refund);
        publish(refund.domainEvents());
        return refund;
    }


    public Refund requestRefund(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId, ChannelRef originalRoute) {
        if (originalRoute == null) {
            return requestRefund(paymentIntentId, amount, reason, businessCaseRef, idempotencyKey, correlationId);
        }
        RefundHandoff handoff = inTransaction(() -> prepareRefundSubmission(paymentIntentId, amount, reason, businessCaseRef, idempotencyKey, correlationId, originalRoute));
        if (!handoff.needsChannel()) return handoff.refund();
        PaymentChannelClient.HandoffRefund channelHandoff = handoffRefund(handoff, correlationId);
        return inTransaction(() -> applyRefundHandoff(handoff, channelHandoff));
    }

    /**
     * First phase of a routed refund. Creates or loads the refund and records its channel
     * keys, so the caller can commit both before the channel sees them. The caller supplies
     * the transaction, which lets an event handler commit its deduplication record with
     * this write while the channel call stays outside.
     */
    RefundHandoff prepareRefundSubmission(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId, ChannelRef originalRoute) {
        Instant now = Instant.now(clock);
        PaymentIntent intent = getIntent(paymentIntentId);
        ChannelRef capturedRoute = intent.channelRef();
        if (capturedRoute == null || capturedRoute.channel() == null
            || originalRoute.channel() == null
            || !ChannelRouter.normalize(capturedRoute.channel()).equals(ChannelRouter.normalize(originalRoute.channel()))
            || originalRoute.channelOrderId() == null || originalRoute.channelOrderId().isBlank()
            || !Objects.equals(capturedRoute.channelOrderId(), originalRoute.channelOrderId())
            || originalRoute.channelTransactionId() == null || originalRoute.channelTransactionId().isBlank()
            || !Objects.equals(capturedRoute.channelTransactionId(), originalRoute.channelTransactionId())) {
            throw new DomainRuleViolation("refund must return to the original payment channel order and transaction");
        }
        Refund refund = requestRefund(paymentIntentId, amount, reason, businessCaseRef, idempotencyKey, correlationId);
        if (refund.status() == com.trainticket.payment.domain.RefundStatus.SETTLED
            || refund.status() == com.trainticket.payment.domain.RefundStatus.SUBMITTED
            || refund.status() == com.trainticket.payment.domain.RefundStatus.MANUAL_REVIEW_REQUIRED) {
            return new RefundHandoff(intent, refund, originalRoute, null, null);
        }
        if (!channelRouter.isEnabled(originalRoute.channel())) {
            int before = refund.domainEvents().size();
            refund.fail("ORIGINAL_CHANNEL_UNAVAILABLE", false, now, commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
            refundRepository.save(refund);
            publishNewEvents(refund.domainEvents(), before);
            recordMoney(PaymentMetricLabels.Operation.REFUND, PaymentMetricLabels.Outcome.REJECTED,
                refund.amount(), originalRoute.channel());
            return new RefundHandoff(intent, refund, originalRoute, null, null);
        }
        String refundKey = keyOrFold(refund.channelRefundIdempotencyKey(), refund.refundId() + ":" + originalRoute.channelOrderId() + ":" + originalRoute.channelTransactionId(), idempotencyKey);
        String submitKey = keyOrFold(refund.channelRefundSubmitIdempotencyKey(), refund.refundId() + ":" + originalRoute.channelOrderId() + ":submit", idempotencyKey);
        // The refund and its submit keys commit before the channel sees them, so
        // a retry or a concurrent attempt reuses the same refund and submit keys.
        refund.rememberChannelRefundKeys(refundKey, submitKey);
        refundRepository.save(refund);
        return new RefundHandoff(intent, refund, originalRoute, refundKey, submitKey);
    }

    /** The channel call itself. Runs outside every transaction, on committed keys only. */
    PaymentChannelClient.HandoffRefund handoffRefund(RefundHandoff handoff, String correlationId) {
        return paymentChannelClient.handoffRefund(handoff.intent(), handoff.refund(), handoff.refundKey(), handoff.submitKey(), correlationId, handoff.originalRoute());
    }

    /**
     * Second phase of a routed refund. Runs in the caller's transaction so the channel
     * result and the caller's own records commit together.
     */
    Refund applyRefundHandoff(RefundHandoff handoff, PaymentChannelClient.HandoffRefund channelHandoff) {
        Refund refund = getRefund(handoff.refund().refundId());
        if (refund.status() == com.trainticket.payment.domain.RefundStatus.SETTLED
            || refund.status() == com.trainticket.payment.domain.RefundStatus.SUBMITTED
            || refund.status() == com.trainticket.payment.domain.RefundStatus.MANUAL_REVIEW_REQUIRED) {
            return refund;
        }
        refund.recordChannelHandoff(channelHandoff.channelRef());
        refundRepository.save(refund);
        return refund;
    }


    @Transactional
    public PaymentIntent captureIntentFromChannel(String paymentIntentId, Money amount, String channel, String channelTransactionId, String channelOrderId, String causationId, String correlationId) {
        return applyChannelCapture(paymentIntentId, amount, channel, channelTransactionId, channelOrderId, causationId, correlationId);
    }

    private PaymentIntent applyChannelCapture(String paymentIntentId, Money amount, String channel, String channelTransactionId, String channelOrderId, String causationId, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        PaymentChannel selectedChannel = channelRouter.requireAvailable(channel, amount);
        channel = selectedChannel.channelId();
        ChannelRef original = intent.channelRef();
        if (original == null || !channel.equals(ChannelRouter.normalize(original.channel()))
            || channelOrderId == null || channelOrderId.isBlank()
            || channelTransactionId == null || channelTransactionId.isBlank()
            || (original.channelOrderId() != null && !original.channelOrderId().equals(channelOrderId))
            || (original.channelTransactionId() != null && !original.channelTransactionId().equals(channelTransactionId))) {
            throw new DomainRuleViolation("channel capture does not match the payment route");
        }
        if (intent.status().name().equals("CAPTURED")) {
            if (!intent.capturedAmount().equals(amount)) {
                throw new DomainRuleViolation("captured payment callback amount mismatch");
            }
            return intent;
        }
        // A channel-confirmed collection against a CANCELLED or EXPIRED intent
        // is not an error and must not be discarded: the money moved. The intent
        // stays terminal (a late capture never revives an order or issues a
        // ticket -- docs/02-domains/payment.md 6.3) and the discrepancy is
        // recorded as a LatePaymentCase for finance/CS to resolve.
        if (intent.isLateCaptureCandidate()) {
            recordLateCapture(intent, amount, channel, channelTransactionId, channelOrderId, causationId, correlationId);
            return intent;
        }
        int before = intent.domainEvents().size();
        intent.recordChannelHandoff(new ChannelRef(channel, channelOrderId, null, channelTransactionId, null, null, null));
        intent.capture(amount, channel, channelTransactionId, Instant.now(clock), commandId(channelOrderId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        recordMoney(PaymentMetricLabels.Operation.CAPTURE, PaymentMetricLabels.Outcome.SUCCEEDED, amount, channel);
        return intent;
    }

    public LatePaymentCase latePaymentForIntent(String paymentIntentId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        ChannelRef route = intent.channelRef();
        if (route == null || route.channelTransactionId() == null) throw new NotFoundException("late payment case not found");
        return latePaymentCaseRepository.findById(LatePaymentCase.caseId(paymentIntentId, ChannelRouter.normalize(route.channel()), route.channelTransactionId()))
            .orElseThrow(() -> new NotFoundException("late payment case not found"));
    }

    @Transactional
    public Refund prepareLatePaymentRefund(String paymentIntentId, String businessRef, String caseRef, String reason, String key, String correlation) {
        PaymentIntent intent = getIntent(paymentIntentId);
        if (!intent.businessRef().equals(requireText(businessRef, "businessRef"))) throw new DomainRuleViolation("late refund business reference mismatch");
        LatePaymentCase lateCase = latePaymentForIntent(paymentIntentId);
        if (lateCase.refundId() != null) {
            Refund existing = getRefund(lateCase.refundId());
            if (!existing.sourceCaseRef().equals(caseRef) || !existing.reasonCode().equals(reason)) {
                throw new DomainRuleViolation("late refund resolution details mismatch");
            }
            return existing;
        }
        if (lateCase.status() != com.trainticket.payment.domain.LatePaymentCaseStatus.OPEN) throw new DomainRuleViolation("late payment case is already resolved");
        Refund refund = Refund.requestLate(intent, lateCase, caseRef, reason, key, Instant.now(clock), commandId(key), correlation);
        lateCase.withRefundId(refund.refundId());
        paymentIntentRepository.save(intent);
        refundRepository.save(refund);
        latePaymentCaseRepository.save(lateCase);
        publish(refund.domainEvents());
        return refund;
    }

    public Refund submitLatePaymentRefund(String paymentIntentId, String correlation) {
        LateRefundHandoff prepared = inTransaction(() -> prepareLateRefundSubmission(paymentIntentId));
        if (prepared.submitKey() == null) return prepared.refund();
        PaymentChannelClient.HandoffRefund channelHandoff = paymentChannelClient.handoffRefund(
            prepared.intent(), prepared.refund(), prepared.refundKey(), prepared.submitKey(), prepared.channelCorrelation(), prepared.route());
        return inTransaction(() -> applyLateRefundHandoff(paymentIntentId, correlation, prepared, channelHandoff));
    }

    private LateRefundHandoff prepareLateRefundSubmission(String paymentIntentId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        LatePaymentCase lateCase = latePaymentForIntent(paymentIntentId);
        Refund refund = getRefund(requireText(lateCase.refundId(), "late refundId"));
        ChannelRef route = refund.channelRef();
        if (refund.status() == com.trainticket.payment.domain.RefundStatus.SETTLED) {
            return new LateRefundHandoff(intent, refund, route, null, null, null);
        }
        String refundKey = foldedUuidV7("late-refund:" + refund.refundId());
        String submitKey = foldedUuidV7("late-refund-submit:" + refund.refundId());
        // The refund and its channel keys commit before the channel sees them.
        refund.rememberChannelRefundKeys(refundKey, submitKey);
        String channelCorrelation = "corr-" + foldedUuidV7("late-refund-correlation:" + refund.refundId());
        refundRepository.save(refund);
        return new LateRefundHandoff(intent, refund, route, refundKey, submitKey, channelCorrelation);
    }

    private Refund applyLateRefundHandoff(String paymentIntentId, String correlation, LateRefundHandoff prepared, PaymentChannelClient.HandoffRefund channelHandoff) {
        Refund refund = getRefund(prepared.refund().refundId());
        if (refund.status() != com.trainticket.payment.domain.RefundStatus.SUBMITTED) {
            refund.recordChannelHandoff(channelHandoff.channelRef());
        }
        refundRepository.save(refund);
        if ("SUCCEEDED".equals(channelHandoff.status())) {
            return settleRefundFromChannel(refund.refundId(), paymentIntentId, refund.amount(), channelHandoff.channelRefundId(), prepared.route().channelOrderId(),
                prepared.route().channel(), prepared.route().channelTransactionId(), channelHandoff.channelRefundTransactionId(), commandId(prepared.submitKey()), correlation);
        }
        return refund;
    }

    // A null submitKey means the refund settled before any channel submission.
    private record LateRefundHandoff(PaymentIntent intent, Refund refund, ChannelRef route, String refundKey, String submitKey, String channelCorrelation) {
    }

    private void recordLateCapture(
        PaymentIntent intent,
        Money amount,
        String channel,
        String channelTransactionId,
        String channelOrderId,
        String causationId,
        String correlationId
    ) {
        LatePaymentCase lateCase = intent.recordLateCapture(
            amount, channel, channelTransactionId, Instant.now(clock), commandId(channelOrderId), causationId, correlationId);
        // The case id is folded from (intent, channel, channelTransactionId), so
        // a second report of the same collection -- ChannelOrderSucceeded and
        // then the reconciliation-driven ChannelOrderRecoveryDetected, which
        // carry different event ids and so survive consumer-side dedup --
        // resolves to the existing case instead of opening a duplicate.
        if (latePaymentCaseRepository.findById(lateCase.latePaymentCaseId()).isPresent()) {
            return;
        }
        latePaymentCaseRepository.save(lateCase);
        publish(lateCase.domainEvents());
        // Money did move even though the intent was already terminal; the
        // reversal that returns it is recorded when the late refund settles.
        recordMoney(PaymentMetricLabels.Operation.CAPTURE, PaymentMetricLabels.Outcome.SUCCEEDED, amount, channel);
    }

    @Transactional
    public PaymentIntent failIntentFromChannel(String paymentIntentId, String reasonCode, String causationId, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.fail(reasonCode, false, Instant.now(clock), commandId(causationId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        recordMoney(PaymentMetricLabels.Operation.CAPTURE, PaymentMetricLabels.Outcome.FAILED,
            intent.amount(), channelOf(intent.channelRef()));
        return intent;
    }

    @Transactional
    public Refund settleRefundFromChannel(String refundId, String paymentIntentId, Money amount, String channelRefundId, String channelOrderId, String channel, String originalChannelTransactionId, String channelRefundTransactionId, String causationId, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        Refund refund = getRefund(refundId);
        if (!refund.paymentIntentId().equals(paymentIntentId)) {
            throw new DomainRuleViolation("channel refund references a different payment intent");
        }
        if (!refund.amount().equals(amount)) {
            throw new DomainRuleViolation("channel refund amount does not match requested refund amount");
        }
        ChannelRef original = intent.channelRef();
        ChannelRef submitted = refund.channelRef();
        if (original == null || channel == null || !ChannelRouter.normalize(channel).equals(ChannelRouter.normalize(original.channel()))
            || !Objects.equals(channelOrderId, original.channelOrderId())
            || (originalChannelTransactionId != null && !Objects.equals(originalChannelTransactionId, original.channelTransactionId()))
            || submitted == null || !Objects.equals(submitted.channelOrderId(), original.channelOrderId())
            || !Objects.equals(submitted.channelTransactionId(), original.channelTransactionId())
            || !ChannelRouter.normalize(submitted.channel()).equals(ChannelRouter.normalize(original.channel()))
            || channelRefundId == null || channelRefundId.isBlank()
            || (submitted.channelRefundId() != null && !submitted.channelRefundId().equals(channelRefundId))) {
            throw new DomainRuleViolation("channel refund does not match the original payment route");
        }
        if (refund.status().name().equals("SETTLED")) {
            if (!Objects.equals(refund.channelRefundTransactionId(), channelRefundTransactionId)) {
                throw new DomainRuleViolation("settled refund references a different channel transaction");
            }
            return refund;
        }
        int beforeRefund = refund.domainEvents().size();
        if (submitted.channelRefundId() == null) {
            refund.recordChannelHandoff(submitted.withRefund(channelRefundId));
        }
        refund.settle(intent, channelRefundTransactionId, Instant.now(clock), commandId(causationId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        refundRepository.save(refund);
        publishNewEvents(refund.domainEvents(), beforeRefund);
        if (intent.isLateCaptureCandidate()) {
            LatePaymentCase lateCase = latePaymentForIntent(paymentIntentId);
            if (!refund.refundId().equals(lateCase.refundId())) throw new DomainRuleViolation("refund is not assigned to the late payment case");
            lateCase.resolve("REFUNDED:" + refund.refundId());
            latePaymentCaseRepository.save(lateCase);
            eventPublisher.publish(new EventEnvelope(com.trainticket.platformkit.messaging.PrefixedIds.newEventId(), "LatePaymentCaseResolved",
                Instant.now(clock), com.trainticket.platformkit.messaging.PrefixedIds.newCorrelationId(),
                com.trainticket.platformkit.messaging.PrefixedIds.newCommandId(), "payment", 1,
                Map.of("latePaymentCaseId", lateCase.latePaymentCaseId(), "paymentIntentId", paymentIntentId, "refundId", refund.refundId(), "resolution", "REFUNDED")));
        }
        // Returning a late collection is a reversal of money the intent never
        // validly held; an ordinary settled refund stays a refund.
        recordMoney(
            intent.isLateCaptureCandidate() ? PaymentMetricLabels.Operation.REVERSAL : PaymentMetricLabels.Operation.REFUND,
            PaymentMetricLabels.Outcome.SUCCEEDED,
            amount,
            channel);
        return refund;
    }

    @Transactional
    public Refund failRefundFromChannel(String refundId, String reasonCode, String causationId, String correlationId) {
        Refund refund = getRefund(refundId);
        int before = refund.domainEvents().size();
        refund.fail(reasonCode, false, Instant.now(clock), commandId(causationId), causationId, correlationId);
        refundRepository.save(refund);
        publishNewEvents(refund.domainEvents(), before);
        // A non-retryable refund failure lands in MANUAL_REVIEW_REQUIRED, which
        // is a rejection rather than a plain failure.
        recordMoney(PaymentMetricLabels.Operation.REFUND, PaymentMetricLabels.refundOutcome(refund.status()),
            refund.amount(), channelOf(refund.channelRef()));
        return refund;
    }

    public ReservationPaymentRequest getReservationPaymentRequest(String segmentBookingId) {        return reservationPaymentRequestRepository.findBySegmentBookingId(requireText(segmentBookingId, "segmentBookingId"))
            .orElseThrow(() -> new NotFoundException("reservation payment request not found"));
    }

    public boolean isSupportedChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return false;
        }
        return channelRouter.isSupportedChannel(channelId);
    }

    public PaymentIntent getIntent(String paymentIntentId) {
        return paymentIntentRepository.findById(requireText(paymentIntentId, "paymentIntentId"))
            .orElseThrow(() -> new NotFoundException("payment intent not found"));
    }

    @Transactional
    public int expireDuePaymentIntents(String idempotencyKey, String correlationId, int limit) {
        int expired = 0;
        Instant now = Instant.now(clock);
        for (PaymentIntent intent : paymentIntentRepository.findExpiredOpenIntents(now, limit)) {
            int before = intent.domainEvents().size();
            intent.expire(now, commandId(idempotencyKey + ":" + intent.paymentIntentId()), commandId(idempotencyKey), correlationId);
            paymentIntentRepository.save(intent);
            publishNewEvents(intent.domainEvents(), before);
            expired++;
        }
        return expired;
    }

    public Refund getRefund(String refundId) {
        return refundRepository.findById(requireText(refundId, "refundId"))
            .orElseThrow(() -> new NotFoundException("refund not found"));
    }

    public List<Refund> refundsForCase(String businessCaseRef) {
        return refundRepository.findByBusinessCaseRef(requireText(businessCaseRef, "businessCaseRef"));
    }

    public PaymentIntent latestIntentForBusiness(String businessRef) {
        return paymentIntentRepository.findLatestByBusinessRef(requireText(businessRef, "businessRef"))
            .orElseThrow(() -> new NotFoundException("payment intent not found for business reference"));
    }

    public Map<String, Object> capturedFacts(String paymentIntentId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        var events = intent.domainEvents().stream()
            .filter(com.trainticket.payment.domain.PaymentCaptured.class::isInstance)
            .map(EventEnvelopeMapper::fromDomainEvent).toList();
        return Map.of("paymentIntentId", intent.paymentIntentId(), "businessRef", intent.businessRef(),
            "payerRef", intent.payerRef(), "purpose", intent.purpose(), "status", intent.status().name(),
            "capturedAmount", Map.of("currency", intent.capturedAmount().currency().getCurrencyCode(), "minorUnits", intent.capturedAmount().toMinorUnits()),
            "refundedAmount", Map.of("currency", intent.refundedAmount().currency().getCurrencyCode(), "minorUnits", intent.refundedAmount().toMinorUnits()),
            "events", events);
    }

    private void publishNewEvents(List<PaymentEvent> events, int previousSize) {
        publish(new ArrayList<>(events.subList(previousSize, events.size())));
    }

    private void publish(List<PaymentEvent> events) {
        for (PaymentEvent event : events) {
            eventPublisher.publish(EventEnvelopeMapper.fromDomainEvent(event));
        }
    }

    private void recordMoney(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        Money amount,
        String channel
    ) {
        if (amount == null || amount.isZero()) {
            return;
        }
        String currency = amount.currency().getCurrencyCode();
        metrics.operation(operation, outcome, currency, channel);
        metrics.amount(operation, outcome, currency, channel, amount.toMinorUnits());
    }

    private static String channelOf(ChannelRef ref) {
        return ref == null ? null : ref.channel();
    }

    private static String keyOrFold(String existing, String material, String fallbackKey) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        // The outbound channel key is ALWAYS folded from the contract
        // material; the caller's inbound key must never leak across the
        // context boundary (round-3 contract finding).
        return foldedUuidV7(material);
    }

    private static String foldedUuidV7(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = java.util.Arrays.copyOf(digest, 16);
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }


    private static String commandId(String idempotencyKey) {
        String value = idempotencyKey == null || idempotencyKey.isBlank() ? java.util.UUID.randomUUID().toString() : idempotencyKey;
        return value.startsWith("cmd-") || value.startsWith("evt-") ? value : "cmd-" + value;
    }


    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
