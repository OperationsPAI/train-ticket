package com.trainticket.journeyorder.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.out.IdentityVerificationPort;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${IDENTITY_VERIFICATION_PREORDER_ENABLED:true}' != 'false'")
public class HttpIdentityVerificationAdapter implements IdentityVerificationPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpIdentityVerificationAdapter.class);
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HttpIdentityVerificationAdapter(ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper, System.getenv().getOrDefault("IDENTITY_VERIFICATION_URL", "http://identity-verification:8080"));
    }

    HttpIdentityVerificationAdapter(HttpClient client, ObjectMapper mapper, String baseUrl) {
        this.client = client;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderIntentId", orderIntentId);
            body.put("accountId", request.accountId());
            body.put("offerId", request.offerId());
            body.put("offerVersion", request.offerVersion());
            body.put("travelerRefs", request.travelerRefs());
            body.put("segmentRefs", request.segmentRefs());
            body.put("journeyDate", request.journeyDate() == null || request.journeyDate().isBlank() ? LocalDate.now().plusDays(1).toString() : request.journeyDate());
            body.put("productCode", request.productCode() == null || request.productCode().isBlank() ? "TRAIN" : request.productCode());
            body.put("requestedEligibilityTypes", List.of());
            body.put("limitPolicyVersion", System.getenv().getOrDefault("IDENTITY_LIMIT_POLICY_VERSION", "limit-v1"));
            body.put("requestedAt", Instant.now().toString());
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/identity-verification/pre-order-checks"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() >= 500) {
                warn(json, "identity verification unavailable");
                throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
            }
            if (response.statusCode() == 409) {
                throw new ApiException(ApiErrorCode.CONFLICT, "Identity verification purchase-limit conflict");
            }
            if (response.statusCode() == 412) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Identity verification precondition failed");
            }
            if (response.statusCode() >= 400) {
                throw new ApiException(ApiErrorCode.DOMAIN_RULE_VIOLATION, "Identity verification rejected order creation");
            }
            String result = json.path("result").asText("DEGRADED");
            if ("DEGRADED".equals(result)) {
                throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification returned degraded result");
            }
            return new PreOrderCheckResult(result, json.path("preOrderCheckId").asText());
        } catch (IOException exception) {
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
        }
    }


    @Override
    public void confirmPreOrderCheck(String preOrderCheckId, String journeyOrderId, String idempotencyKey, String correlationId) {
        if (preOrderCheckId == null || preOrderCheckId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("journeyOrderId", journeyOrderId);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/identity-verification/pre-order-checks/" + preOrderCheckId + "/confirm"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                JsonNode json = mapper.readTree(response.body().isBlank() ? "{}" : response.body());
                warn(json, "identity verification confirm failed");
            }
        } catch (IOException exception) {
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void releasePreOrderCheck(String preOrderCheckId, String releaseReason, String idempotencyKey, String correlationId) {
        if (preOrderCheckId == null || preOrderCheckId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("releaseReason", releaseReason);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/identity-verification/pre-order-checks/" + preOrderCheckId + "/release"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                JsonNode json = mapper.readTree(response.body().isBlank() ? "{}" : response.body());
                warn(json, "identity verification release failed");
            }
        } catch (IOException exception) {
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void warn(JsonNode json, String fallback) {
        String code = json.path("details").path("domainCode").asText(json.path("code").asText("UNAVAILABLE"));
        String message = json.path("message").asText(fallback);
        LOGGER.warn("identity verification downstream failure code={} message={}", code, message);
    }
}
