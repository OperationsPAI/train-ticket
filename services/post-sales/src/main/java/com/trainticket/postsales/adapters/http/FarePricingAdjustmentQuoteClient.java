package com.trainticket.postsales.adapters.http;

import com.trainticket.postsales.application.AdjustmentQuotePort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FarePricingAdjustmentQuoteClient implements AdjustmentQuotePort {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final RestClient orderClient;
    private final RestClient offerClient;

    public FarePricingAdjustmentQuoteClient(@Value("${post-sales.fare-pricing.base-url}") String baseUrl,
                                            @Value("${JOURNEY_ORDER_BASE_URL:http://journey-order:8080}") String orderUrl,
                                            @Value("${OFFER_MANAGEMENT_BASE_URL:http://offer-management:8080}") String offerUrl,
                                            RestClient.Builder restClientBuilder) {
        // Plain HTTP/1.1 factory: the JDK client's default h2c upgrade is
        // rejected by fare-pricing's uvicorn/h11 with "Invalid HTTP request".
        // The builder must come from the container: java-kit installs the
        // trace-propagation interceptor on RestClient.Builder beans only.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).baseUrl(baseUrl).build();
        this.orderClient = restClientBuilder.clone().requestFactory(requestFactory).baseUrl(orderUrl).build();
        this.offerClient = restClientBuilder.clone().requestFactory(requestFactory).baseUrl(offerUrl).build();
    }

    @Override
    public Optional<AdjustmentQuoteResult> compute(AdjustmentQuoteRequest request) {
        Map<String, Object> order = orderClient.get().uri("/api/v1/journey-orders/{id}", request.journeyOrderId())
            .retrieve().body(MAP_TYPE);
        if (order == null || !request.journeyOrderId().equals(order.get("orderId")) || !(order.get("offerId") instanceof String offerId)) {
            throw new IllegalStateException("order response is missing its authoritative offer");
        }
        Map<String, Object> offer = offerClient.get().uri("/api/v1/offers/{id}", offerId).retrieve().body(MAP_TYPE);
        if (offer == null || !offerId.equals(offer.get("offerId"))) {
            throw new IllegalStateException("offer response differs from the order reference");
        }
        Map<String, Object> price = asMap(offer.get("priceSnapshot"));
        if (!(price.get("fareQuoteRef") instanceof String quoteId) || quoteId.isBlank()) {
            throw new IllegalStateException("purchased offer has no original fare quote");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purpose", request.purpose());
        body.put("originalQuoteId", quoteId);
        body.put("entitlementIds", request.entitlementIds());
        body.put("journeyOrderId", request.journeyOrderId());
        body.put("segmentRefs", request.segmentRefs());
        if ("CHANGE".equals(request.purpose())) {
            if (request.targetOfferId() == null || request.targetOfferId().isBlank()) throw new com.trainticket.postsales.domain.DomainRuleViolation("change requires a selected target offer");
            Map<String, Object> target = offerClient.get().uri("/api/v1/offers/{id}",request.targetOfferId()).retrieve().body(MAP_TYPE);
            if (target == null || !request.targetOfferId().equals(target.get("offerId"))
                || !java.util.Objects.equals(order.get("accountId"),target.get("accountId"))) throw new IllegalStateException("target offer does not belong to the original account");
            if (!(target.get("expiresAt") instanceof String expiry) || !java.time.Instant.now().isBefore(java.time.Instant.parse(expiry))) throw new IllegalStateException("target offer has expired");
            var travelers = asMap(target.get("passengerMix")).get("travelers");
            java.util.Set<String> targetTravelers = new java.util.HashSet<>();
            if (travelers instanceof java.util.List<?> list) for (Object traveler : list) targetTravelers.add(String.valueOf(asMap(traveler).get("travelerId")));
            Object originalTravelers = order.get("travelerRefs");
            if (!(originalTravelers instanceof java.util.List<?> list) || !targetTravelers.equals(new java.util.HashSet<>(list))) throw new IllegalStateException("target offer travelers differ from the original order");
            Map<String, Object> targetPrice = asMap(target.get("priceSnapshot"));
            if (!(targetPrice.get("fareQuoteRef") instanceof String targetQuoteId) || targetQuoteId.isBlank()) throw new IllegalStateException("target offer has no fare quote");
            body.put("targetQuoteId",targetQuoteId);
        }
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/adjustment-quotes")
                .header("Idempotency-Key", request.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);
        if (response == null || response.get("adjustmentQuoteId") == null || !"QUOTED".equals(response.get("status"))) {
            throw new IllegalStateException("adjustment quote did not reach QUOTED");
        }
        Map<String, Object> refundable = asMap(response.get("refundableAmount"));
        Map<String, Object> amountDue = asMap(response.get("amountDue"));
        return Optional.of(new AdjustmentQuoteResult(
            String.valueOf(response.get("adjustmentQuoteId")),
            String.valueOf(response.get("status")),
            minorUnits(refundable),
            currency(refundable),
            minorUnits(amountDue),
            currency(amountDue),
            com.trainticket.postsales.application.PostSalesPolicyContextMapper.fromOrderSnapshot(request.journeyOrderId(),order)
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long minorUnits(Map<String, Object> money) {
        Object value = money.get("minorUnits");
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalStateException("adjustment quote has no valid amount");
        }
        return number.longValue();
    }

    private static String currency(Map<String, Object> money) {
        Object value = money.get("currency");
        if (!(value instanceof String code) || code.isBlank()) {
            throw new IllegalStateException("adjustment quote has no currency");
        }
        return code;
    }
}
