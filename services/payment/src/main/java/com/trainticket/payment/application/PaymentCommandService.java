package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentEvent;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import java.time.Clock;
import java.time.Instant;
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
    private static final String DEFAULT_CHANNEL = "internal_payment_channel";

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final ReservationPaymentRequestRepository reservationPaymentRequestRepository;

    public PaymentCommandService(
        Clock clock,
        EventPublisher eventPublisher,
        PaymentIntentRepository paymentIntentRepository,
        RefundRepository refundRepository,
        ReservationPaymentRequestRepository reservationPaymentRequestRepository
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.paymentIntentRepository = Objects.requireNonNull(paymentIntentRepository, "paymentIntentRepository is required");
        this.refundRepository = Objects.requireNonNull(refundRepository, "refundRepository is required");
        this.reservationPaymentRequestRepository = Objects.requireNonNull(reservationPaymentRequestRepository, "reservationPaymentRequestRepository is required");
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
        Instant now = Instant.now(clock);
        PaymentIntent intent = PaymentIntent.create(businessRef, purpose, amount, payerRef, now.plusSeconds(900), idempotencyKey, now, commandId(idempotencyKey), correlationId);
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
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.capture(intent.amount().subtract(intent.capturedAmount()), DEFAULT_CHANNEL, "txn-" + idempotencyKey, Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        paymentIntentRepository.save(intent);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    @Transactional
    public PaymentIntent authorizeIntent(String paymentIntentId, String idempotencyKey, String correlationId, String channelTransactionRef) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.authorize(intent.amount(), DEFAULT_CHANNEL, "auth-" + requireText(channelTransactionRef, "channelTransactionRef"), Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
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

    public ReservationPaymentRequest getReservationPaymentRequest(String segmentBookingId) {
        return reservationPaymentRequestRepository.findBySegmentBookingId(requireText(segmentBookingId, "segmentBookingId"))
            .orElseThrow(() -> new NotFoundException("reservation payment request not found"));
    }

    public PaymentIntent getIntent(String paymentIntentId) {
        return paymentIntentRepository.findById(requireText(paymentIntentId, "paymentIntentId"))
            .orElseThrow(() -> new NotFoundException("payment intent not found"));
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

    private static String commandId(String idempotencyKey) {
        return "cmd-" + requireText(idempotencyKey, "idempotencyKey");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
