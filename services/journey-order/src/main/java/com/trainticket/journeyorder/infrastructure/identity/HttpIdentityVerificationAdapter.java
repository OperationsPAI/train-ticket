package com.trainticket.journeyorder.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.out.IdentityVerificationPort;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${IDENTITY_VERIFICATION_PREORDER_ENABLED:true}' != 'false'")
public class HttpIdentityVerificationAdapter implements IdentityVerificationPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpIdentityVerificationAdapter.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpIdentityVerificationAdapter(ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        this(mapper, restClientBuilder, System.getenv().getOrDefault("IDENTITY_VERIFICATION_URL", "http://identity-verification:8080"));
    }

    HttpIdentityVerificationAdapter(ObjectMapper mapper, RestClient.Builder restClientBuilder, String baseUrl) {
        this.mapper = mapper;
        // Plain HTTP/1.1 factory: uvicorn rejects the JDK client's default h2c
        // upgrade with a plain-text body that breaks JSON error parsing.
        //
        // The builder must come from the container: java-kit installs the
        // trace-propagation interceptor on RestClient.Builder beans only, so a
        // hand-built client would silently drop the outbound W3C traceparent
        // and identity-verification would open a new trace.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restClient = restClientBuilder.requestFactory(requestFactory).baseUrl(normalizedBaseUrl).build();
    }

    @Override
    public PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId) {
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

        Exchanged exchanged = exchange("/api/v1/identity-verification/pre-order-checks", idempotencyKey, correlationId, body);
        JsonNode json = exchanged.json();
        int statusCode = exchanged.statusCode();
        if (statusCode >= 500) {
            warn(json, "identity verification unavailable");
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
        }
        if (statusCode == 409) {
            throw new ApiException(ApiErrorCode.CONFLICT, "Identity verification purchase-limit conflict");
        }
        if (statusCode == 412) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Identity verification precondition failed");
        }
        if (statusCode >= 400) {
            throw new ApiException(ApiErrorCode.DOMAIN_RULE_VIOLATION, "Identity verification rejected order creation");
        }
        String result = json.path("result").asText("DEGRADED");
        if ("DEGRADED".equals(result)) {
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification returned degraded result");
        }
        return new PreOrderCheckResult(result, json.path("preOrderCheckId").asText());
    }

    @Override
    public void confirmPreOrderCheck(String preOrderCheckId, String journeyOrderId, String idempotencyKey, String correlationId) {
        if (preOrderCheckId == null || preOrderCheckId.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journeyOrderId", journeyOrderId);
        try {
            Exchanged exchanged = exchange("/api/v1/identity-verification/pre-order-checks/" + preOrderCheckId + "/confirm", idempotencyKey, correlationId, body);
            if (exchanged.statusCode() >= 400) {
                warn(exchanged.json(), "identity verification confirm failed");
            }
        } catch (ApiException exception) {
            // Best-effort confirm: the original adapter logged and returned on
            // transport failure rather than failing the caller.
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
        }
    }

    @Override
    public void releasePreOrderCheck(String preOrderCheckId, String releaseReason, String idempotencyKey, String correlationId) {
        if (preOrderCheckId == null || preOrderCheckId.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("releaseReason", releaseReason);
        try {
            Exchanged exchanged = exchange("/api/v1/identity-verification/pre-order-checks/" + preOrderCheckId + "/release", idempotencyKey, correlationId, body);
            if (exchanged.statusCode() >= 400) {
                warn(exchanged.json(), "identity verification release failed");
            }
        } catch (ApiException exception) {
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
        }
    }

    /**
     * POST {@code body} to {@code path} and return the status and parsed body
     * without throwing on 4xx/5xx: every caller above inspects the status itself.
     *
     * The interceptor java-kit installed on the injected builder adds the
     * outbound {@code traceparent}; the correlation and idempotency headers are
     * set here exactly as before.
     */
    private Exchanged exchange(String path, String idempotencyKey, String correlationId, Map<String, Object> body) {
        try {
            return restClient.post()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", correlationId)
                .body(body)
                .exchange((request, response) -> {
                    String responseBody = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return new Exchanged(response.getStatusCode().value(), mapper.readTree(responseBody.isBlank() ? "{}" : responseBody));
                }, false);
        } catch (ResourceAccessException exception) {
            // Transport-level failure: same shape as the previous IOException path.
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) {
                throw apiException;
            }
            LOGGER.warn("identity verification downstream failure code={} message={}", "IO_ERROR", exception.getMessage());
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Identity verification unavailable");
        }
    }

    private record Exchanged(int statusCode, JsonNode json) { }

    private void warn(JsonNode json, String fallback) {
        String code = json.path("details").path("domainCode").asText(json.path("code").asText("UNAVAILABLE"));
        String message = json.path("message").asText(fallback);
        LOGGER.warn("identity verification downstream failure code={} message={}", code, message);
    }
}
