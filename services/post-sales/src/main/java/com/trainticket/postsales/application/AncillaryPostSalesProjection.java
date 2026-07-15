package com.trainticket.postsales.application;

import java.time.Instant;
import java.util.Map;

public record AncillaryPostSalesProjection(
    String ancillaryOrderItemId,
    String journeyOrderId,
    String travelerRef,
    String segmentRef,
    String catalogItemId,
    String serviceType,
    String status,
    String previousStatus,
    ExternalMoney payableAmount,
    ExternalMoney refundableAmount,
    ExternalMoney refundedAmount,
    String recommendation,
    String reasonCode,
    String postSalesCaseId,
    String refundRef,
    String failureCode,
    Boolean compensable,
    Map<String, Object> fulfillmentFact,
    String sourceEventId,
    String lastEventId,
    String lastEventType,
    Instant lastOccurredAt,
    long aggregateVersion
) {
    public AncillaryPostSalesProjection {
        ancillaryOrderItemId = requireText(ancillaryOrderItemId, "ancillaryOrderItemId");
        journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        travelerRef = requireText(travelerRef, "travelerRef");
        status = requireText(status, "status");
        lastEventId = requireText(lastEventId, "lastEventId");
        lastEventType = requireText(lastEventType, "lastEventType");
        if (fulfillmentFact != null) {
            fulfillmentFact = Map.copyOf(fulfillmentFact);
        }
    }

    public static AncillaryPostSalesProjection fromEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Map<?, ?> payload
    ) {
        Map<String, Object> fulfillmentFact = objectMap(payload.get("fulfillmentFact"));
        return new AncillaryPostSalesProjection(
            text(payload, "ancillaryOrderItemId"),
            text(payload, "journeyOrderId"),
            text(payload, "travelerRef"),
            optionalText(payload, "segmentRef"),
            optionalText(payload, "catalogItemId"),
            optionalText(payload, "serviceType"),
            text(payload, "status"),
            optionalText(payload, "previousStatus"),
            ExternalMoney.fromPayloadValue(payload.get("payableAmount")),
            ExternalMoney.fromPayloadValue(payload.get("refundableAmount")),
            ExternalMoney.fromPayloadValue(payload.get("refundedAmount")),
            optionalText(payload, "recommendation"),
            optionalText(payload, "reasonCode"),
            optionalText(payload, "postSalesCaseId"),
            optionalText(payload, "refundRef"),
            optionalText(payload, "failureCode"),
            optionalBoolean(payload, "compensable"),
            fulfillmentFact,
            optionalText(payload, "sourceEventId"),
            eventId,
            eventType,
            occurredAt,
            optionalLong(payload, "aggregateVersion")
        );
    }

    public AncillaryPostSalesProjection withPostSalesCaseId(String linkedPostSalesCaseId) {
        return new AncillaryPostSalesProjection(
            ancillaryOrderItemId,
            journeyOrderId,
            travelerRef,
            segmentRef,
            catalogItemId,
            serviceType,
            status,
            previousStatus,
            payableAmount,
            refundableAmount,
            refundedAmount,
            recommendation,
            reasonCode,
            linkedPostSalesCaseId,
            refundRef,
            failureCode,
            compensable,
            fulfillmentFact,
            sourceEventId,
            lastEventId,
            lastEventType,
            lastOccurredAt,
            aggregateVersion
        );
    }

    private static String text(Map<?, ?> payload, String field) {
        String value = optionalText(payload, field);
        if (value == null) {
            throw new IllegalArgumentException("event payload missing " + field);
        }
        return value;
    }

    private static String optionalText(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Boolean optionalBoolean(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private static long optionalLong(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return (Map<String, Object>) map;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
