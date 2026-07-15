package com.trainticket.postsales.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.postsales.domain.PostSalesCase;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PostSalesExternalEventPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostSalesExternalEventPolicy.class);
    private static final Set<String> ANCILLARY_EVENT_TYPES = Set.of(
        "AncillaryOrderItemFulfilled",
        "AncillaryOrderItemFailed",
        "AncillaryOrderItemCancelled",
        "AncillaryOrderItemRefundPending",
        "AncillaryOrderItemRefunded",
        "AncillaryFulfillmentFactRecorded"
    );
    private static final Set<String> DISPATCH_EVENT_TYPES = Set.of(
        "DispatchUserCancelled",
        "DispatchNoShowRecorded",
        "RideEnded",
        "DispatchFailed"
    );

    private final PostSalesApplicationService applicationService;

    public PostSalesExternalEventPolicy(PostSalesApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    public boolean handles(String eventType) {
        return ANCILLARY_EVENT_TYPES.contains(eventType) || DISPATCH_EVENT_TYPES.contains(eventType);
    }

    public void handle(EventEnvelope envelope) {
        if (ANCILLARY_EVENT_TYPES.contains(envelope.eventType())) {
            payload(envelope).ifPresentOrElse(
                payload -> handleAncillary(envelope, payload),
                () -> ackSkipMalformedPayload(envelope, "payload-not-object")
            );
            return;
        }
        if (DISPATCH_EVENT_TYPES.contains(envelope.eventType())) {
            payload(envelope).ifPresentOrElse(
                payload -> applicationService.recordDispatchProjection(dispatchProjection(envelope, payload)),
                () -> ackSkipMalformedPayload(envelope, "payload-not-object")
            );
        }
    }

    private void handleAncillary(EventEnvelope envelope, Map<?, ?> payload) {
        AncillaryPostSalesProjection projection = AncillaryPostSalesProjection.fromEvent(
            envelope.eventId(),
            envelope.eventType(),
            envelope.occurredAt(),
            payload
        );
        if ("AncillaryOrderItemCancelled".equals(envelope.eventType()) && isPositive(projection.refundableAmount())) {
            PostSalesCase linkedCase = applicationService.linkRefundForAncillaryCancellation(projection);
            applicationService.recordAncillaryProjection(projection.withPostSalesCaseId(linkedCase.caseId()));
            return;
        }
        applicationService.recordAncillaryProjection(projection);
        if ("AncillaryOrderItemFailed".equals(envelope.eventType()) && Boolean.TRUE.equals(projection.compensable())) {
            applicationService.openCompensationForAncillaryFailure(projection);
        }
    }

    private DispatchPostSalesProjection dispatchProjection(EventEnvelope envelope, Map<?, ?> payload) {
        return new DispatchPostSalesProjection(
            text(payload, "rideRequestId"),
            optionalText(payload, "rideAssignmentId"),
            text(payload, "riderAccountId"),
            text(payload, "travelerRef"),
            text(payload, "pickupRef"),
            text(payload, "dropoffRef"),
            optionalText(payload, "driverRef"),
            optionalText(payload, "vehicleRef"),
            optionalText(payload, "intentFingerprint"),
            optionalText(payload, "finalFareRef"),
            optionalText(payload, "reason"),
            text(payload, "status"),
            optionalText(payload, "previousStatus"),
            instant(payload, "startedAt"),
            instant(payload, "endedAt"),
            instant(payload, "cancelledAt"),
            instant(payload, "recordedAt"),
            instant(payload, "failedAt"),
            envelope.eventId(),
            envelope.eventType(),
            envelope.occurredAt()
        );
    }

    private static Optional<Map<?, ?>> payload(EventEnvelope envelope) {
        return envelope.payload() instanceof Map<?, ?> map ? Optional.of(map) : Optional.empty();
    }

    private static boolean isPositive(ExternalMoney money) {
        return money != null && money.minorUnits() > 0;
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

    private static Instant instant(Map<?, ?> payload, String field) {
        String value = optionalText(payload, field);
        return value == null ? null : Instant.parse(value);
    }

    private static void ackSkipMalformedPayload(EventEnvelope envelope, String reason) {
        LOGGER.warn(
            "ack-skip post-sales external event={} eventId={} producer={} reason={}",
            envelope.eventType(),
            envelope.eventId(),
            envelope.producer(),
            reason
        );
    }
}
