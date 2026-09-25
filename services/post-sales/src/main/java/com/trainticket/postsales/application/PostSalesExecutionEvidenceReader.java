package com.trainticket.postsales.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.DomainRuleViolation;
import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesExecutionEvidence;
import com.trainticket.postsales.domain.PostSalesStepType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Reads owning-service state without issuing any downstream command. */
@Service
public final class PostSalesExecutionEvidenceReader {
    private static final Set<String> ACCEPTED_REFUND_STATES = Set.of("REQUESTED", "SUBMITTED", "SETTLED");
    private static final Set<PostSalesCaseType> SUPPORTED_TYPES = Set.of(
        PostSalesCaseType.REFUND, PostSalesCaseType.CANCELLATION, PostSalesCaseType.REBOOK);
    private static final Set<PostSalesStepType> REQUIRED_STEPS = Set.of(
        PostSalesStepType.DECISION_CONFIRMED,
        PostSalesStepType.VOID_ENTITLEMENT,
        PostSalesStepType.CANCEL_SEGMENT,
        PostSalesStepType.RELEASE_CAPACITY,
        PostSalesStepType.REQUEST_REFUND,
        PostSalesStepType.APPLY_RESULT);

    private final PostSalesApplicationService cases;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String paymentUrl;
    private final String ticketingUrl;
    private final String bookingUrl;
    private final String capacityUrl;

    public PostSalesExecutionEvidenceReader(
        PostSalesApplicationService cases,
        ObjectMapper mapper,
        RestClient.Builder builder,
        @Value("${PAYMENT_BASE_URL:http://payment:8080}") String paymentUrl,
        @Value("${ENTITLEMENT_TICKETING_BASE_URL:http://entitlement-ticketing:8080}") String ticketingUrl,
        @Value("${BOOKING_ORCHESTRATION_BASE_URL:http://booking-orchestration:8080}") String bookingUrl,
        @Value("${CAPACITY_AVAILABILITY_BASE_URL:http://capacity-availability:8080}") String capacityUrl
    ) {
        this.cases = cases;
        this.mapper = mapper;
        this.paymentUrl = paymentUrl;
        this.ticketingUrl = ticketingUrl;
        this.bookingUrl = bookingUrl;
        this.capacityUrl = capacityUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build();
    }

    public PostSalesExecutionEvidence read(String caseId, String correlationId) {
        return read(cases.get(caseId), correlationId);
    }

    PostSalesExecutionEvidence read(PostSalesCase current, String correlationId) {
        require(current.status() == PostSalesCaseStatus.EXECUTING,
            "execution reconciliation requires an EXECUTING case");
        require(SUPPORTED_TYPES.contains(current.caseType()),
            "execution reconciliation supports refund-like cases only");
        require(current.decision() != null, "execution reconciliation requires a frozen decision");
        require(current.executionPlan().stream().map(step -> step.type()).collect(java.util.stream.Collectors.toSet()).containsAll(REQUIRED_STEPS),
            "execution reconciliation requires the complete persisted refund plan");
        require(!current.scope().entitlementRefs().isEmpty() && !current.scope().orderItemRefs().isEmpty(),
            "execution reconciliation requires explicit entitlement and booking scope");

        List<PostSalesExecutionEvidence.EntitlementEvidence> entitlements = new ArrayList<>();
        Map<String, PostSalesExecutionEvidence.EntitlementEvidence> entitlementByBooking = new HashMap<>();
        for (String entitlementId : current.scope().entitlementRefs()) {
            JsonNode fact = get(ticketingUrl, "/api/v1/entitlements/" + entitlementId, correlationId);
            String bookingId = text(fact, "segmentBookingId");
            String travelerRef = text(fact, "travelerRef");
            String segmentRef = text(fact, "segmentRef");
            require(entitlementId.equals(text(fact, "entitlementId"))
                    && current.journeyOrderId().equals(text(fact, "journeyOrderId"))
                    && "VOIDED".equals(text(fact, "status"))
                    && current.scope().orderItemRefs().contains(bookingId)
                    && current.scope().travelerRefs().contains(travelerRef)
                    && current.scope().segmentRefs().contains(segmentRef),
                "scoped entitlement is not authoritatively voided for this order and booking");
            Instant voidedAt = instant(fact, "voidedAt");
            require(current.createdAt() == null || !voidedAt.isBefore(current.createdAt()),
                "entitlement was voided before the post-sales case existed");
            var evidence = new PostSalesExecutionEvidence.EntitlementEvidence(
                entitlementId, bookingId, travelerRef, segmentRef, voidedAt);
            require(entitlementByBooking.putIfAbsent(bookingId, evidence) == null,
                "multiple scoped entitlements reference the same booking");
            entitlements.add(evidence);
        }
        require(entitlementByBooking.keySet().equals(new HashSet<>(current.scope().orderItemRefs())),
            "voided entitlement evidence does not cover every scoped booking");

        List<PostSalesExecutionEvidence.BookingEvidence> bookings = new ArrayList<>();
        List<PostSalesExecutionEvidence.CapacityEvidence> capacity = new ArrayList<>();
        for (String bookingId : current.scope().orderItemRefs()) {
            JsonNode fact = get(bookingUrl, "/api/v1/internal/segment-bookings/" + bookingId, correlationId);
            var entitlement = entitlementByBooking.get(bookingId);
            String holdId = text(fact, "capacityHoldId");
            require(bookingId.equals(text(fact, "segmentBookingId"))
                    && current.journeyOrderId().equals(text(fact, "journeyOrderId"))
                    && "CANCELLED".equals(text(fact, "status"))
                    && entitlement.entitlementId().equals(text(fact, "entitlementId"))
                    && entitlement.travelerRef().equals(text(fact, "travelerRef"))
                    && entitlement.segmentRef().equals(text(fact, "segmentRef")),
                "scoped booking is not authoritatively cancelled for the voided entitlement");
            bookings.add(new PostSalesExecutionEvidence.BookingEvidence(
                bookingId, entitlement.entitlementId(), entitlement.travelerRef(), entitlement.segmentRef(), holdId));

            JsonNode hold = getOptional(capacityUrl, "/api/v1/capacity-holds/" + holdId, correlationId);
            if (hold == null) {
                // Absence is accepted only here, after the linked entitlement and booking
                // have both been verified in their terminal states.
                capacity.add(new PostSalesExecutionEvidence.CapacityEvidence(holdId, bookingId, true));
            } else {
                require(holdId.equals(text(hold, "holdId"))
                        && bookingId.equals(text(hold, "segmentBookingRef"))
                        && entitlement.segmentRef().equals(text(hold, "segmentRef"))
                        && "RELEASED".equals(text(hold, "status")),
                    "capacity hold still exists without authoritative RELEASED state");
                capacity.add(new PostSalesExecutionEvidence.CapacityEvidence(holdId, bookingId, false));
            }
        }

        List<PostSalesExecutionEvidence.RefundEvidence> refunds = refundEvidence(current, correlationId);
        return new PostSalesExecutionEvidence(current.caseId(), current.journeyOrderId(),
            entitlements, bookings, capacity, refunds);
    }

    private List<PostSalesExecutionEvidence.RefundEvidence> refundEvidence(PostSalesCase current, String correlationId) {
        String canonicalCaseId = PostSalesMapper.prefixed(current.caseId(), "psc-");
        JsonNode page = get(paymentUrl, "/api/v1/refunds?businessCaseRef=" + canonicalCaseId, correlationId);
        require(page.path("items").isArray() && page.path("total").isIntegralNumber()
                && page.path("total").asInt() == page.path("items").size(),
            "refund lookup returned an incomplete page");
        Money approved = current.decision().amountSnapshot().refundAmount();
        if (approved.isZero()) {
            require(page.path("items").isEmpty(), "zero approved refund has unexpected payment refund effects");
            return List.of();
        }

        List<PostSalesExecutionEvidence.RefundEvidence> refunds = new ArrayList<>();
        Set<String> refundIds = new HashSet<>();
        Map<String, Money> actualByPayment = new HashMap<>();
        long total = 0;
        for (JsonNode fact : page.path("items")) {
            String refundId = text(fact, "refundId");
            String paymentIntentId = text(fact, "paymentIntentId");
            String status = text(fact, "status");
            Money amount = RefundFundingService.money(fact.path("amount"));
            require(refundIds.add(refundId)
                    && canonicalCaseId.equals(text(fact, "businessCaseRef"))
                    && ACCEPTED_REFUND_STATES.contains(status)
                    && approved.currency().equals(amount.currency())
                    && actualByPayment.putIfAbsent(paymentIntentId, amount) == null,
                "refund evidence conflicts with the approved case");
            total = Math.addExact(total, amount.toMinorUnits());
            refunds.add(new PostSalesExecutionEvidence.RefundEvidence(refundId, paymentIntentId, amount, status));
        }
        require(total == approved.toMinorUnits(), "payment refunds do not equal the approved amount");
        if (current.refundPayments().isEmpty()) {
            require(refunds.size() == 1, "unallocated approved refund requires one authoritative payment refund");
        } else {
            Map<String, Money> expectedByPayment = new HashMap<>();
            for (Map<String, Object> allocation : current.refundPayments()) {
                String paymentIntentId = requiredString(allocation.get("paymentIntentId"), "refund paymentIntentId");
                Money amount = RefundFundingService.money(mapper.valueToTree(allocation.get("amount")));
                require(expectedByPayment.putIfAbsent(paymentIntentId, amount) == null,
                    "approved refund allocation contains duplicate payment intent");
            }
            require(expectedByPayment.equals(actualByPayment),
                "payment refunds differ from the frozen refund allocation");
        }
        return refunds;
    }

    private JsonNode get(String baseUrl, String path, String correlationId) {
        JsonNode result = getOptional(baseUrl, path, correlationId);
        if (result == null) throw new DomainRuleViolation("required reconciliation fact is missing: " + path);
        return result;
    }

    private JsonNode getOptional(String baseUrl, String path, String correlationId) {
        return client.get().uri(baseUrl + path).header("X-Correlation-Id", correlationId).exchange((request, response) -> {
            if (response.getStatusCode().value() == 404) return null;
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("execution reconciliation lookup returned " + response.getStatusCode());
            }
            try {
                return mapper.readTree(response.getBody());
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("execution reconciliation fact is not valid JSON", error);
            }
        });
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        require(!value.isBlank(), "reconciliation fact is missing " + field);
        return value;
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (java.time.format.DateTimeParseException error) {
            throw new DomainRuleViolation("reconciliation fact has invalid " + field);
        }
    }

    private static String requiredString(Object value, String name) {
        require(value instanceof String text && !text.isBlank(), name + " is required");
        return (String) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new DomainRuleViolation(message);
    }
}
