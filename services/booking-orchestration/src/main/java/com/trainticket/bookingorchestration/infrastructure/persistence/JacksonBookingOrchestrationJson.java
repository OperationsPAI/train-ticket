package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.bookingorchestration.domain.BookingSagaStep;
import com.trainticket.bookingorchestration.domain.BookingStepStatus;
import com.trainticket.bookingorchestration.domain.ProviderReference;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import com.trainticket.bookingorchestration.domain.SegmentBookingStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class JacksonBookingOrchestrationJson {
    private JacksonBookingOrchestrationJson() {}

    record BookingSagaSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static BookingSagaSnapshot of(ObjectNode data) { return new BookingSagaSnapshot(data); }
    }

    record SegmentBookingSnapshot(@JsonValue ObjectNode data) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static SegmentBookingSnapshot of(ObjectNode data) { return new SegmentBookingSnapshot(data); }
    }

    static BookingSagaSnapshot sagaSnapshot(BookingSaga saga, String correlationId, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("sagaId", saga.sagaId());
        node.put("journeyOrderId", saga.journeyOrderId());
        node.put("idempotencyKey", saga.idempotencyKey());
        node.put("status", saga.status().name());
        if (correlationId != null && !correlationId.isBlank()) node.put("correlationId", correlationId);
        saga.terminalReason().ifPresent(reason -> node.put("terminalReason", reason));
        ArrayNode steps = node.putArray("steps");
        for (BookingSagaStep step : saga.steps()) {
            ObjectNode stepNode = steps.addObject();
            stepNode.put("name", step.name());
            stepNode.put("idempotencyKey", step.idempotencyKey());
            stepNode.put("timeoutMillis", step.timeout().toMillis());
            stepNode.put("retryLimit", step.retryLimit());
            stepNode.put("compensationAction", step.compensationAction());
            stepNode.put("status", step.status().name());
            stepNode.put("attemptNumber", step.attemptNumber());
            step.failureReason().ifPresent(reason -> stepNode.put("failureReason", reason));
        }
        return new BookingSagaSnapshot(node);
    }

    static BookingSaga toSaga(BookingSagaSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode node = snapshot.data();
        List<BookingSagaStep> steps = new ArrayList<>();
        for (var stepNode : node.withArray("steps")) {
            steps.add(BookingSagaStep.rehydrate(
                stepNode.path("name").asText(),
                stepNode.path("idempotencyKey").asText(),
                Duration.ofMillis(stepNode.path("timeoutMillis").asLong()),
                stepNode.path("retryLimit").asInt(),
                stepNode.path("compensationAction").asText(),
                BookingStepStatus.valueOf(stepNode.path("status").asText()),
                stepNode.path("attemptNumber").asInt(),
                stepNode.path("failureReason").isMissingNode() ? null : stepNode.path("failureReason").asText()
            ));
        }
        String terminalReason = node.path("terminalReason").isMissingNode() ? null : node.path("terminalReason").asText();
        return BookingSaga.rehydrate(node.path("sagaId").asText(), node.path("journeyOrderId").asText(),
            node.path("idempotencyKey").asText(), BookingSagaStatus.valueOf(node.path("status").asText()),
            steps, terminalReason, Clock.systemUTC());
    }

    static SegmentBookingSnapshot segmentSnapshot(SegmentBooking booking, String sagaId, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("segmentBookingId", booking.segmentBookingId());
        node.put("sagaId", sagaId);
        node.put("journeyOrderId", booking.journeyOrderId());
        node.put("offerItemRef", booking.offerItemRef());
        node.put("segmentRef", booking.segmentRef());
        node.put("travelerRef", booking.travelerRef());
        node.put("bookingPurpose", booking.bookingPurpose());
        node.put("idempotencyKey", booking.idempotencyKey());
        node.put("status", booking.status().name());
        booking.capacityHoldId().ifPresent(value -> node.put("capacityHoldId", value));
        booking.entitlementId().ifPresent(value -> node.put("entitlementId", value));
        booking.failureReason().ifPresent(value -> node.put("failureReason", value));
        booking.cancellationReason().ifPresent(value -> node.put("cancellationReason", value));
        booking.providerReference().ifPresent(ref -> {
            ObjectNode provider = node.putObject("providerReference");
            provider.put("providerId", ref.providerId());
            provider.put("reservationId", ref.reservationId());
            provider.put("displayReference", ref.displayReference());
        });
        return new SegmentBookingSnapshot(node);
    }

    static SegmentBooking toSegmentBooking(SegmentBookingSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode node = snapshot.data();
        ProviderReference providerReference = null;
        if (node.has("providerReference")) {
            ObjectNode provider = (ObjectNode) node.get("providerReference");
            providerReference = new ProviderReference(provider.path("providerId").asText(), provider.path("reservationId").asText(), provider.path("displayReference").asText());
        }
        return SegmentBooking.rehydrate(
            node.path("segmentBookingId").asText(), node.path("journeyOrderId").asText(), node.path("offerItemRef").asText(),
            node.path("segmentRef").asText(), node.path("travelerRef").asText(), node.path("bookingPurpose").asText(),
            SegmentBookingStatus.valueOf(node.path("status").asText()), textOrNull(node, "capacityHoldId"), providerReference,
            textOrNull(node, "entitlementId"), textOrNull(node, "failureReason"), textOrNull(node, "cancellationReason"), Clock.systemUTC());
    }

    private static String textOrNull(ObjectNode node, String field) { return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null; }
}
