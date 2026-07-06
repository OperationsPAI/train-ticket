package com.trainticket.postsales.adapters.http;

import com.trainticket.postsales.application.AdjustmentQuotePort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FarePricingAdjustmentQuoteClient implements AdjustmentQuotePort {
    private static final Logger log = LoggerFactory.getLogger(FarePricingAdjustmentQuoteClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;

    public FarePricingAdjustmentQuoteClient(@Value("${post-sales.fare-pricing.base-url}") String baseUrl) {
        // Plain HTTP/1.1 factory: the JDK client's default h2c upgrade is
        // rejected by fare-pricing's uvicorn/h11 with "Invalid HTTP request".
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).build();
    }

    @Override
    public Optional<AdjustmentQuoteResult> compute(AdjustmentQuoteRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purpose", request.purpose());
        body.put("entitlementIds", request.entitlementIds());
        body.put("journeyOrderId", request.journeyOrderId());
        body.put("segmentRefs", request.segmentRefs());
        try {
            Map<String, Object> response = restClient.post()
                .uri("/api/v1/adjustment-quotes")
                .header("Idempotency-Key", request.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);
            if (response == null || response.get("adjustmentQuoteId") == null) {
                return Optional.empty();
            }
            Map<String, Object> refundable = asMap(response.get("refundableAmount"));
            Map<String, Object> amountDue = asMap(response.get("amountDue"));
            return Optional.of(new AdjustmentQuoteResult(
                String.valueOf(response.get("adjustmentQuoteId")),
                String.valueOf(response.get("status")),
                minorUnits(refundable),
                currency(refundable),
                minorUnits(amountDue),
                currency(amountDue)
            ));
        } catch (RuntimeException ex) {
            log.warn("adjustment quote unavailable for case idempotency key {}: {}", request.idempotencyKey(), ex.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long minorUnits(Map<String, Object> money) {
        Object value = money.get("minorUnits");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String currency(Map<String, Object> money) {
        Object value = money.get("currency");
        return value instanceof String code && !code.isBlank() ? code : "CNY";
    }
}
