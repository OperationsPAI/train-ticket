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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class PaymentCommandService {
    private static final String DEFAULT_CHANNEL = "internal_payment_channel";

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final Map<String, PaymentIntent> intents = new ConcurrentHashMap<>();
    private final Map<String, Refund> refunds = new ConcurrentHashMap<>();
    private final Map<String, ReservationPaymentRequest> reservationPaymentRequests = new ConcurrentHashMap<>();

    public PaymentCommandService(Clock clock, EventPublisher eventPublisher) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
    }

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
        reservationPaymentRequests.putIfAbsent(request.segmentBookingId(), request);
        return reservationPaymentRequests.get(request.segmentBookingId());
    }

    public PaymentIntent createIntent(String businessRef, String purpose, Money amount, String payerRef, String idempotencyKey, String correlationId) {
        Instant now = Instant.now(clock);
        PaymentIntent intent = PaymentIntent.create(businessRef, purpose, amount, payerRef, now.plusSeconds(900), idempotencyKey, now, commandId(idempotencyKey), correlationId);
        intents.put(intent.paymentIntentId(), intent);
        publish(intent.domainEvents());
        return intent;
    }

    public PaymentIntent cancelIntent(String paymentIntentId, String reason, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.cancel(reason, Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    public PaymentIntent captureIntent(String paymentIntentId, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.capture(intent.amount().subtract(intent.capturedAmount()), DEFAULT_CHANNEL, "txn-" + idempotencyKey, Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    public PaymentIntent authorizeIntent(String paymentIntentId, String idempotencyKey, String correlationId, String channelTransactionRef) {
        PaymentIntent intent = getIntent(paymentIntentId);
        int before = intent.domainEvents().size();
        intent.authorize(intent.amount(), DEFAULT_CHANNEL, "auth-" + requireText(channelTransactionRef, "channelTransactionRef"), Instant.now(clock), commandId(idempotencyKey), commandId(idempotencyKey), correlationId);
        publishNewEvents(intent.domainEvents(), before);
        return intent;
    }

    public Refund requestRefund(String paymentIntentId, Money amount, String reason, String businessCaseRef, String idempotencyKey, String correlationId) {
        PaymentIntent intent = getIntent(paymentIntentId);
        Refund refund = Refund.request(intent, amount, businessCaseRef == null || businessCaseRef.isBlank() ? "case-" + idempotencyKey : businessCaseRef, reason, idempotencyKey, Instant.now(clock), commandId(idempotencyKey), correlationId);
        refunds.put(refund.refundId(), refund);
        publish(refund.domainEvents());
        return refund;
    }

    public ReservationPaymentRequest getReservationPaymentRequest(String segmentBookingId) {
        ReservationPaymentRequest request = reservationPaymentRequests.get(requireText(segmentBookingId, "segmentBookingId"));
        if (request == null) {
            throw new NotFoundException("reservation payment request not found");
        }
        return request;
    }

    public PaymentIntent getIntent(String paymentIntentId) {
        PaymentIntent intent = intents.get(requireText(paymentIntentId, "paymentIntentId"));
        if (intent == null) {
            throw new NotFoundException("payment intent not found");
        }
        return intent;
    }

    public Refund getRefund(String refundId) {
        Refund refund = refunds.get(requireText(refundId, "refundId"));
        if (refund == null) {
            throw new NotFoundException("refund not found");
        }
        return refund;
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
