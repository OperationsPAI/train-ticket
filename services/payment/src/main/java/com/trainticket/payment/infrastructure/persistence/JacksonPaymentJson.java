package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.application.EventEnvelopeMapper;
import com.trainticket.payment.application.ReservationPaymentRequest;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.PaymentEvent;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentAuthorized;
import com.trainticket.payment.domain.PaymentCaptured;
import com.trainticket.payment.domain.PaymentFailed;
import com.trainticket.payment.domain.PaymentIntentCancelled;
import com.trainticket.payment.domain.PaymentIntentCreated;
import com.trainticket.payment.domain.PaymentIntentExpired;
import com.trainticket.payment.domain.PaymentIntentStatus;
import com.trainticket.payment.domain.RefundFailed;
import com.trainticket.payment.domain.RefundRequested;
import com.trainticket.payment.domain.RefundSettled;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class JacksonPaymentJson {
    private JacksonPaymentJson() {
    }

    static PaymentIntentSnapshot intentSnapshot(PaymentIntent intent, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("paymentIntentId", intent.paymentIntentId());
        root.put("businessRef", intent.businessRef());
        root.put("purpose", intent.purpose());
        root.set("amount", money(intent.amount(), objectMapper));
        root.put("payerRef", intent.payerRef());
        root.put("expiresAt", intent.expiresAt().toString());
        root.put("idempotencyKey", intent.idempotencyKey());
        root.put("status", intent.status().name());
        root.set("authorizedAmount", money(intent.authorizedAmount(), objectMapper));
        root.set("capturedAmount", money(intent.capturedAmount(), objectMapper));
        root.set("refundedAmount", money(intent.refundedAmount(), objectMapper));
        ArrayNode refs = root.putArray("channelTransactionRefs");
        intent.channelTransactionRefs().stream().sorted().forEach(refs::add);
        if (intent.channelRef() != null) {
            root.set("channelRef", objectMapper.valueToTree(EventEnvelopeMapper.channelRef(intent.channelRef())));
        }
        ArrayNode events = root.putArray("domainEvents");
        for (PaymentEvent event : intent.domainEvents()) {
            events.add(objectMapper.valueToTree(EventEnvelopeMapper.fromDomainEvent(event)));
        }
        return new PaymentIntentSnapshot(root);
    }

    static PaymentIntent toIntent(PaymentIntentSnapshot snapshot, ObjectMapper objectMapper) {
        ObjectNode root = snapshot.data();
        Set<String> refs = new LinkedHashSet<>();
        root.path("channelTransactionRefs").forEach(node -> refs.add(node.asText()));
        List<PaymentEvent> events = new ArrayList<>();
        root.path("domainEvents").forEach(node -> events.add(toPaymentEvent(objectMapper.convertValue(node, EventEnvelope.class), node.path("payload"))));
        return PaymentIntent.rehydrate(
            text(root, "paymentIntentId"),
            text(root, "businessRef"),
            text(root, "purpose"),
            money(root.path("amount")),
            text(root, "payerRef"),
            Instant.parse(text(root, "expiresAt")),
            text(root, "idempotencyKey"),
            PaymentIntentStatus.valueOf(text(root, "status")),
            money(root.path("authorizedAmount")),
            money(root.path("capturedAmount")),
            money(root.path("refundedAmount")),
            refs,
            events,
            channelRef(root.path("channelRef"))
        );
    }

    static RefundSnapshot refundSnapshot(Refund refund, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("refundId", refund.refundId());
        root.put("paymentIntentId", refund.paymentIntentId());
        root.set("amount", money(refund.amount(), objectMapper));
        root.put("sourceCaseRef", refund.sourceCaseRef());
        root.put("reasonCode", refund.reasonCode());
        root.put("idempotencyKey", refund.idempotencyKey());
        root.put("status", refund.status().name());
        if (refund.channelRefundTransactionId() == null) {
            root.putNull("channelRefundTransactionId");
        } else {
            root.put("channelRefundTransactionId", refund.channelRefundTransactionId());
        }
        if (refund.channelRef() != null) {
            root.set("channelRef", objectMapper.valueToTree(EventEnvelopeMapper.channelRef(refund.channelRef())));
        }
        root.put("attemptCount", refund.attemptCount());
        ArrayNode events = root.putArray("domainEvents");
        for (PaymentEvent event : refund.domainEvents()) {
            events.add(objectMapper.valueToTree(EventEnvelopeMapper.fromDomainEvent(event)));
        }
        return new RefundSnapshot(root);
    }

    static Refund toRefund(RefundSnapshot snapshot, ObjectMapper objectMapper) {
        ObjectNode root = snapshot.data();
        List<PaymentEvent> events = new ArrayList<>();
        root.path("domainEvents").forEach(node -> events.add(toPaymentEvent(objectMapper.convertValue(node, EventEnvelope.class), node.path("payload"))));
        return Refund.rehydrate(
            text(root, "refundId"),
            text(root, "paymentIntentId"),
            money(root.path("amount")),
            text(root, "sourceCaseRef"),
            text(root, "reasonCode"),
            text(root, "idempotencyKey"),
            RefundStatus.valueOf(text(root, "status")),
            root.path("channelRefundTransactionId").isNull() ? null : root.path("channelRefundTransactionId").asText(null),
            root.path("attemptCount").asInt(0),
            events,
            channelRef(root.path("channelRef"))
        );
    }

    static String reservationJson(ReservationPaymentRequest request, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", request.eventId());
        root.put("segmentBookingId", request.segmentBookingId());
        root.put("journeyOrderId", request.journeyOrderId());
        root.put("segmentRef", request.segmentRef());
        root.put("travelerRef", request.travelerRef());
        root.put("idempotencyKey", request.idempotencyKey());
        root.put("correlationId", request.correlationId());
        root.put("requestedAt", request.requestedAt().toString());
        return write(root, objectMapper);
    }

    static ReservationPaymentRequest toReservation(String json, ObjectMapper objectMapper) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(json);
            return new ReservationPaymentRequest(
                text(root, "eventId"),
                text(root, "segmentBookingId"),
                text(root, "journeyOrderId"),
                text(root, "segmentRef"),
                text(root, "travelerRef"),
                text(root, "idempotencyKey"),
                text(root, "correlationId"),
                Instant.parse(text(root, "requestedAt"))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("reservation request JSON could not be decoded", exception);
        }
    }

    static String write(ObjectNode root, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payment snapshot could not be encoded", exception);
        }
    }

    private static ObjectNode money(Money money, ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("currency", money.currency().getCurrencyCode());
        node.put("minorUnits", money.toMinorUnits());
        return node;
    }

    private static Money money(com.fasterxml.jackson.databind.JsonNode node) {
        return Money.fromMinorUnits(node.path("minorUnits").asLong(), text(node, "currency"));
    }

    private static ChannelRef channelRef(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new ChannelRef(
            node.path("channel").asText(null),
            node.path("channelOrderId").asText(null),
            node.path("channelRefundId").asText(null),
            node.path("channelTransactionId").asText(null),
            node.path("channelRefundTransactionId").asText(null),
            node.path("channelStatementId").asText(null),
            node.path("faultSeedRef").asText(null)
        );
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is missing from payment snapshot");
        }
        return value;
    }

    // @JsonValue/@JsonCreator keep the column payload as the aggregate JSON
    // itself; a bare record would wrap it as {"data": {...}} and break every
    // raw JSONB query (e.g. the businessRef refund lookup).
    record PaymentIntentSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static PaymentIntentSnapshot of(ObjectNode data) {
            return new PaymentIntentSnapshot(data);
        }
    }

    record RefundSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static RefundSnapshot of(ObjectNode data) {
            return new RefundSnapshot(data);
        }
    }

    private static PaymentEvent toPaymentEvent(EventEnvelope envelope, com.fasterxml.jackson.databind.JsonNode payload) {
        return switch (envelope.eventType()) {
            case "PaymentIntentCreated" -> new PaymentIntentCreated(envelope, text(payload, "paymentIntentId"), text(payload, "businessRef"), text(payload, "purpose"), money(payload.path("amount")), text(payload, "payerRef"), text(payload, "idempotencyKey"));
            case "PaymentAuthorized" -> new PaymentAuthorized(envelope, text(payload, "paymentIntentId"), money(payload.path("authorizedAmount")), text(payload, "channel"), text(payload, "channelTransactionId"));
            case "PaymentCaptured" -> new PaymentCaptured(envelope, text(payload, "paymentIntentId"), text(payload, "businessRef"), money(payload.path("capturedAmount")), text(payload, "channel"), text(payload, "channelTransactionId"), channelRef(payload.path("channelRef")));
            case "PaymentFailed" -> new PaymentFailed(envelope, text(payload, "paymentIntentId"), text(payload, "reasonCode"), payload.path("retryable").asBoolean());
            case "PaymentIntentCancelled" -> new PaymentIntentCancelled(envelope, text(payload, "paymentIntentId"), text(payload, "reason"));
            case "PaymentIntentExpired" -> new PaymentIntentExpired(envelope, text(payload, "paymentIntentId"));
            case "RefundRequested" -> new RefundRequested(envelope, text(payload, "refundId"), text(payload, "paymentIntentId"), money(payload.path("amount")), text(payload, "businessCaseRef"), text(payload, "reason"), text(payload, "idempotencyKey"));
            case "RefundSettled" -> new RefundSettled(envelope, text(payload, "refundId"), text(payload, "paymentIntentId"), money(payload.path("amount")), text(payload, "channelRefundTransactionId"), channelRef(payload.path("channelRef")));
            case "RefundFailed" -> new RefundFailed(envelope, text(payload, "refundId"), text(payload, "paymentIntentId"), text(payload, "reason"));
            default -> throw new IllegalStateException("unsupported persisted payment event type: " + envelope.eventType());
        };
    }


}
