package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.DomainRuleViolation;
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
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.payment.domain.ports.RefundRepository;
import com.trainticket.payment.domain.ports.ReservationPaymentRequestRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCommandService {
    // RULING (wave A2): captures without an explicit channel route through the
    // default SIM channel so saga-driven and loadgen traffic exercises the real
    // handoff; the legacy internal path remains reachable only by explicit env.
    private static final String DEFAULT_CHANNEL = System.getenv().getOrDefault("PAYMENT_DEFAULT_CHANNEL", "ALIPAY_SIM");

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final ReservationPaymentRequestRepository reservationPaymentRequestRepository;
    private final PaymentChannelClient paymentChannelClient;
    private final ChannelRouter channelRouter;

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        PaymentChannelClient paymentChannelClient
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, paymentChannelClient, ChannelRouter.defaults());
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository,
        PaymentChannelClient paymentChannelClient,
        ChannelRouter channelRouter
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.paymentIntentRepository = Objects.requireNonNull(paymentIntentRepository, "paymentIntentRepository is required");
        this.refundRepository = Objects.requireNonNull(refundRepository, "refundRepository is required");
        this.reservationPaymentRequestRepository = Objects.requireNonNull(reservationPaymentRequestRepository, "reservationPaymentRequestRepository is required");
        this.paymentChannelClient = Objects.requireNonNull(paymentChannelClient, "paymentChannelClient is required");
        this.channelRouter = Objects.requireNonNull(channelRouter, "channelRouter is required");
    }

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository
    ) {
        this(clock, eventPublisher, paymentIntentRepository, refundRepository, reservationPaymentRequestRepository, new NoopPaymentChannelClient());
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

    @Transactional
    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId) {
        return captureIntent(paymentIntentId, idempotencyKey, correlationId, (String) null);
    }

    @Transactional
    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId, String requestedChannel) {
        PaymentIntent intent = getIntent(paymentIntentId);
        String channel = requestedChannel == null || requestedChannel.isBlank()
            ? (intent.channelRef() == null || intent.channelRef().channel() == null ? DEFAULT_CHANNEL : intent.channelRef().channel())
            : requestedChannel;
        PaymentChannel selectedChannel = channelRouter.requireAvailable(channel, intent.amount());
        channel = selectedChannel.channelId();
        ChannelRef requestedRef = new ChannelRef(channel, null, null, null, null, null, null);
        String orderKey = keyOrFold(intent.channelOrderIdempotencyKey(), intent.paymentIntentId() + ":1", idempotencyKey);
        String submitKey = keyOrFold(intent.channelOrderSubmitIdempotencyKey(), intent.paymentIntentId() + ":1:submit", idempotencyKey);
        intent.rememberChannelOrderKeys(orderKey, submitKey);
        paymentIntentRepository.save(intent);
        PaymentChannelClient.HandoffOrder handoff = paymentChannelClient.handoffCapture(intent, orderKey, submitKey, correlationId, requestedRef);
        intent.recordChannelHandoff(handoff.channelRef());
        if ("SUCCEEDED".equals(handoff.status()) && handoff.channelTransactionId() != null) {
            int before = intent.domainEvents().size();
            intent.capture(intent.amount().subtract(intent.capturedAmount()), channel, handoff.channelTransactionId(), Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
            paymentIntentRepository.save(intent);
            publishNewEvents(intent.domainEvents(), before);
        } else {
            paymentIntentRepository.save(intent);
        }
        return intent;
    }

    @Transactional
    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId, ChannelRef requestedRef) {
        if (requestedRef == null) {
            return captureIntent(paymentIntentId, idempotencyKey, correlationId, (String) null);
        }
        PaymentIntent intent = getIntent(paymentIntentId);
        PaymentChannel selectedChannel = channelRouter.requireAvailable(requestedRef.channel(), intent.amount());
        requestedRef = new ChannelRef(selectedChannel.channelId(), requestedRef.channelOrderId(), requestedRef.channelRefundId(), requestedRef.channelTransactionId(), requestedRef.channelRefundTransactionId(), requestedRef.channelStatementId(), requestedRef.faultSeedRef());
        String orderKey = keyOrFold(intent.channelOrderIdempotencyKey(), intent.paymentIntentId() + ":1", idempotencyKey);
        String submitKey = keyOrFold(intent.channelOrderSubmitIdempotencyKey(), intent.paymentIntentId() + ":1:submit", idempotencyKey);
        intent.rememberChannelOrderKeys(orderKey, submitKey);
        paymentIntentRepository.save(intent);
        PaymentChannelClient.HandoffOrder handoff = paymentChannelClient.handoffCapture(intent, orderKey, submitKey, correlationId, requestedRef);
        intent.recordChannelHandoff(handoff.channelRef());
        paymentIntentRepository.save(intent);
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

    /** Latest captured (or created) intent for a business reference, used
     * when post-sales approvals reference the order rather than the intent. */
    public java.util.Optional<String> findIntentIdByBusinessRef(String businessRef) {
        return paymentIntentRepository.findLatestByBusinessRef(businessRef).map(PaymentIntent::paymentIntentId);
    }

    @Transactional
    public Refund requestRefund(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        Refund refund = Refund.request(intent, amount, businessCaseRef == null || businessCaseRef.isBlank() ? "case-" + idempotencyKey : businessCaseRef, reason, idempotencyKey, Instant.now(clock), commandId(idempotencyKey), correlationId);
        refundRepository.save(refund);
        publish(refund.domainEvents());
        return refund;
    }


    @Transactional
    public Refund requestRefund(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId, ChannelRef originalRoute) {
        Instant now = Instant.now(clock);
        Refund refund = requestRefund(paymentIntentId, amount, reason, businessCaseRef, idempotencyKey, correlationId);
        if (originalRoute == null) {
            return refund;
        }
        PaymentIntent intent = getIntent(paymentIntentId);
        ChannelRef capturedRoute = intent.channelRef();
        if (capturedRoute == null || capturedRoute.channel() == null || !ChannelRouter.normalize(capturedRoute.channel()).equals(ChannelRouter.normalize(originalRoute.channel()))) {
            throw new DomainRuleViolation("refund must return to original payment channel");
        }
        if (!channelRouter.isEnabled(originalRoute.channel())) {
            int before = refund.domainEvents().size();
            refund.fail("ORIGINAL_CHANNEL_UNAVAILABLE", false, now, commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
            refundRepository.save(refund);
            publishNewEvents(refund.domainEvents(), before);
            return refund;
        }
        String refundKey = keyOrFold(refund.channelRefundIdempotencyKey(), refund.refundId() + ":" + originalRoute.channelOrderId() + ":" + originalRoute.channelTransactionId(), idempotencyKey);
        String submitKey = keyOrFold(refund.channelRefundSubmitIdempotencyKey(), refund.refundId() + ":" + originalRoute.channelOrderId() + ":submit", idempotencyKey);
        refund.rememberChannelRefundKeys(refundKey, submitKey);
        refundRepository.save(refund);
        PaymentChannelClient.HandoffRefund handoff = paymentChannelClient.handoffRefund(intent, refund, refundKey, submitKey, correlationId, originalRoute);
        refund.recordChannelHandoff(handoff.channelRef());
        refundRepository.save(refund);
        return refund;
    }


    @Transactional
    public PaymentIntent captureIntentFromChannel(String paymentIntentId, Money amount, String channel, String channelTransactionId, String channelOrderId, String causationId, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        PaymentChannel selectedChannel = channelRouter.requireAvailable(channel, amount);
        channel = selectedChannel.channelId();
        if (intent.status().name().equals("CAPTURED")) {
            return intent;
        }
        int before = intent.domainEvents().size();
        intent.recordChannelHandoff(new ChannelRef(channel, channelOrderId, null, channelTransactionId, null, null, null));
        intent.capture(amount, channel, channelTransactionId, Instant.now(clock), commandId(channelOrderId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    @Transactional
    public PaymentIntent failIntentFromChannel(String paymentIntentId, String reasonCode, String causationId, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.fail(reasonCode, false, Instant.now(clock), commandId(causationId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    @Transactional
    public Refund settleRefundFromChannel(String refundId, String paymentIntentId, Money amount, String channelRefundId, String channelOrderId, String channel, String originalChannelTransactionId, String channelRefundTransactionId, String causationId, String correlationId) {
        Refund refund = getRefund(refundId);
        PaymentIntent intent = getIntent(paymentIntentId);
        if (refund.status().name().equals("SETTLED")) {
            return refund;
        }
        if (!refund.amount().equals(amount)) {
            throw new DomainRuleViolation("channel refund amount does not match requested refund amount");
        }
        int beforeRefund = refund.domainEvents().size();
        if (channel != null && !channel.isBlank() && refund.channelRef() == null) {
            channelRouter.requireEnabled(channel);
            refund.recordChannelHandoff(new ChannelRef(ChannelRouter.normalize(channel), channelOrderId, channelRefundId, originalChannelTransactionId, null, null, null));
        }
        refund.settle(intent, channelRefundTransactionId, Instant.now(clock), commandId(causationId), causationId, correlationId);
        paymentIntentRepository.save(intent);
        refundRepository.save(refund);
        publishNewEvents(refund.domainEvents(), beforeRefund);
        return refund;
    }

    @Transactional
    public Refund failRefundFromChannel(String refundId, String reasonCode, String causationId, String correlationId) {
        Refund refund = getRefund(refundId);
        int before = refund.domainEvents().size();
        refund.fail(reasonCode, false, Instant.now(clock), commandId(causationId), causationId, correlationId);
        refundRepository.save(refund);
        publishNewEvents(refund.domainEvents(), before);
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

    private void publishNewEvents(List<PaymentEvent> events, int previousSize) {
        publish(new ArrayList<>(events.subList(previousSize, events.size())));
    }

    private void publish(List<PaymentEvent> events) {
        for (PaymentEvent event : events) {
            eventPublisher.publish(EventEnvelopeMapper.fromDomainEvent(event));
        }
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
