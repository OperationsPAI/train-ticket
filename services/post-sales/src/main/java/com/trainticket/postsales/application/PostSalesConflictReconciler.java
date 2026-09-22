package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.trainticket.postsales.domain.*;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class PostSalesConflictReconciler {
    private final PostSalesRepository repository;
    private final EventPublisher publisher;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final RestClient payment;
    private final RestClient ticketing;

    public PostSalesConflictReconciler(PostSalesRepository repository, EventPublisher publisher, Clock clock, ObjectMapper mapper,
            @Value("${PAYMENT_BASE_URL:http://payment:8080}") String paymentUrl,
            @Value("${ENTITLEMENT_TICKETING_BASE_URL:http://entitlement-ticketing:8080}") String ticketingUrl,
            RestClient.Builder builder) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        payment = builder.clone().requestFactory(factory).baseUrl(paymentUrl).build();
        ticketing = builder.clone().requestFactory(factory).baseUrl(ticketingUrl).build();
    }

    @Transactional
    public PostSalesCase reconcile(String caseId, String correlationId) {
        var initial = requiredCase(caseId);
        repository.lockOrder(initial.journeyOrderId());
        var target = requiredCase(caseId);
        if (target.status() == PostSalesCaseStatus.FAILED && target.terminalReason() != null
                && target.terminalReason().startsWith("SCOPE_ALREADY_REFUNDED:")) return target;
        if (target.caseType() != PostSalesCaseType.CHANGE || target.status() != PostSalesCaseStatus.EXECUTING
                || target.decision() == null || !target.decision().amountSnapshot().refundAmount().isZero()
                || !target.releasedOrderItems().isEmpty()
                || target.executionPlan().stream().anyMatch(step -> step.status() == PostSalesStepStatus.SUCCEEDED)) {
            throw new DomainRuleViolation("conflict reconciliation requires an uncompleted change with no refund effects");
        }
        if (target.scope().entitlementRefs().isEmpty() || target.scope().orderItemRefs().isEmpty()) {
            throw new DomainRuleViolation("conflict reconciliation requires explicit ticket and booking references");
        }
        var prior = repository.findAppliedCasesForOrder(target.journeyOrderId()).stream()
            .filter(item -> item.caseType() == PostSalesCaseType.REFUND || item.caseType() == PostSalesCaseType.CANCELLATION)
            .filter(item -> item.scope().entitlementRefs().containsAll(target.scope().entitlementRefs())
                && item.scope().orderItemRefs().containsAll(target.scope().orderItemRefs())
                && item.scope().travelerRefs().containsAll(target.scope().travelerRefs()))
            .toList();
        if (prior.size() != 1 || prior.getFirst().decision() == null) {
            throw new DomainRuleViolation("conflict reconciliation requires one applied refund covering the entire change");
        }
        var original = prior.getFirst();
        var approved = original.decision().amountSnapshot().refundAmount();
        var originalRefunds = refunds(original.caseId());
        long settled = 0;
        for (var refund : originalRefunds.path("items")) {
            if (!"SETTLED".equals(refund.path("status").asText())
                    || !approved.currency().getCurrencyCode().equals(refund.path("amount").path("currency").asText())
                    || !PostSalesMapper.prefixed(original.caseId(), "psc-").equals(refund.path("businessCaseRef").asText())) {
                throw new DomainRuleViolation("original refund has not fully settled");
            }
            settled = Math.addExact(settled, refund.path("amount").required("minorUnits").longValue());
        }
        if (settled != approved.toMinorUnits()) throw new DomainRuleViolation("settled refund differs from the approved amount");
        if (refunds(target.caseId()).path("total").asInt(-1) != 0) {
            throw new DomainRuleViolation("change has refund effects requiring separate reconciliation");
        }
        try {
            payment.get().uri("/api/v1/payment-intents/by-business/{id}", PostSalesMapper.prefixed(target.caseId(), "psc-"))
                .retrieve().toBodilessEntity();
            throw new DomainRuleViolation("change has a payment intent requiring separate reconciliation");
        } catch (HttpClientErrorException.NotFound expected) {
            // The payment service authoritatively reports no intent for this case.
        }
        for (String entitlement : target.scope().entitlementRefs()) {
            JsonNode fact = readJson(ticketing.get().uri("/api/v1/entitlements/{id}", entitlement).retrieve().body(String.class));
            if (!entitlement.equals(fact.path("entitlementId").asText()) || !"VOIDED".equals(fact.path("status").asText())
                    || !target.journeyOrderId().equals(fact.path("journeyOrderId").asText())
                    || !target.scope().orderItemRefs().contains(fact.path("segmentBookingId").asText())
                    || !target.scope().travelerRefs().contains(fact.path("travelerRef").asText())) {
                throw new DomainRuleViolation("all changed tickets must already be voided for the original refund");
            }
            if (target.createdAt() == null || !fact.path("voidedAt").isTextual()
                    || Instant.parse(fact.path("voidedAt").asText()).isAfter(target.createdAt())) {
                throw new DomainRuleViolation("ticket voiding must precede the conflicting change");
            }
        }
        target.failCase("SCOPE_ALREADY_REFUNDED:" + PostSalesMapper.prefixed(original.caseId(), "psc-"),
            "RECONCILE_APPLIED_SCOPE", clock.instant(), PrefixedIds.newCommandId(), PrefixedIds.newCommandId(), correlationId);
        repository.save(target);
        for (var event : target.domainEvents()) publisher.publish(PostSalesMapper.toEnvelope(event, target));
        return target;
    }

    private PostSalesCase requiredCase(String id) {
        return repository.findById(PostSalesMapper.stripCasePrefix(id)).orElseThrow(() -> new CaseNotFoundException(id));
    }

    private JsonNode refunds(String id) {
        JsonNode page = readJson(payment.get().uri(builder -> builder.path("/api/v1/refunds")
            .queryParam("businessCaseRef", PostSalesMapper.prefixed(id,"psc-")).build()).retrieve().body(String.class));
        if (!page.path("items").isArray() || page.path("total").asInt(-1) != page.path("items").size()) {
            throw new IllegalStateException("refund lookup returned an incomplete page");
        }
        return page;
    }

    private JsonNode readJson(String json) {
        try { return mapper.readTree(Objects.requireNonNull(json, "fact response body is required")); }
        catch (JsonProcessingException error) { throw new IllegalStateException("fact response is not valid JSON", error); }
    }
}
