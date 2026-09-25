package com.trainticket.walletpromotion.adapters.http;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.application.BenefitScopeVerifier;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitUseReference;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public final class AuthoritativeBenefitScopeVerifier implements BenefitScopeVerifier {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() { };

    private final RestClient journeyOrders;
    private final RestClient postSales;
    private final RestClient offers;
    private final RestClient fares;

    @Autowired
    public AuthoritativeBenefitScopeVerifier(
        RestClient.Builder builder,
        @Value("${JOURNEY_ORDER_BASE_URL:http://journey-order:8080}") String journeyOrderBaseUrl,
        @Value("${POST_SALES_BASE_URL:http://post-sales:8080}") String postSalesBaseUrl,
        @Value("${OFFER_MANAGEMENT_BASE_URL:http://offer-management:8080}") String offerBaseUrl,
        @Value("${FARE_PRICING_BASE_URL:http://fare-pricing:8080}") String fareBaseUrl
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.journeyOrders = builder.clone().requestFactory(requestFactory).baseUrl(journeyOrderBaseUrl).build();
        this.postSales = builder.clone().requestFactory(requestFactory).baseUrl(postSalesBaseUrl).build();
        this.offers = builder.clone().requestFactory(requestFactory).baseUrl(offerBaseUrl).build();
        this.fares = builder.clone().requestFactory(requestFactory).baseUrl(fareBaseUrl).build();
    }

    AuthoritativeBenefitScopeVerifier(RestClient journeyOrders, RestClient postSales, RestClient offers, RestClient fares) {
        this.journeyOrders = journeyOrders;
        this.postSales = postSales;
        this.offers = offers;
        this.fares = fares;
    }

    @Override
    public void verify(PromotionInstrument benefit, BenefitUseReference useReference, BusinessReason reason) {
        if (useReference == null) {
            throw precondition("useReference is required to verify benefit scope");
        }
        Map<String, Object> order = get(journeyOrders, "/api/v1/journey-orders/{id}", useReference.orderId(), "order");
        requireEqual(useReference.orderId(), text(order, "orderId", "order"), "order response identity mismatch");
        requireEqual(benefit.accountId(), text(order, "accountId", "order"), "benefit account does not own the order");
        String orderCurrency = text(map(order, "monetarySummary", "order"), "currency", "order monetarySummary");
        requireEqual(benefit.applicableScope().currency(), orderCurrency.toUpperCase(), "benefit currency does not match the order");
        if ("CANCELLED".equals(String.valueOf(order.get("status")))) {
            throw precondition("cancelled order cannot consume a benefit");
        }

        Set<String> orderSegments = strings(order.get("segmentRefs"), "order.segmentRefs");
        if (!orderSegments.containsAll(useReference.segmentRefs())) {
            throw precondition("requested service segment does not belong to the order");
        }

        Map<String, Object> postSalesCase = null;
        if (useReference.postSalesCaseId() != null) {
            postSalesCase = get(postSales, "/api/v1/post-sales-cases/{id}", useReference.postSalesCaseId(), "post-sales case");
            requireEqual(useReference.postSalesCaseId(), text(postSalesCase, "caseId", "post-sales case"), "post-sales case identity mismatch");
            requireEqual(useReference.orderId(), text(postSalesCase, "journeyOrderId", "post-sales case"), "post-sales case does not belong to the order");
            requireEqual(benefit.accountId(), text(postSalesCase, "actorRef", "post-sales case"), "post-sales case actor does not own the benefit");
            Set<String> caseSegments = strings(map(postSalesCase, "scope", "post-sales case").get("segmentRefs"), "post-sales case scope.segmentRefs");
            if (!useReference.segmentRefs().isEmpty() && !caseSegments.containsAll(useReference.segmentRefs())) {
                throw precondition("requested service segment is outside the post-sales case scope");
            }
        }

        verifyReason(reason, useReference);
        verifyApplicableScope(benefit, useReference, order, postSalesCase);
    }

    private void verifyApplicableScope(
        PromotionInstrument benefit,
        BenefitUseReference useReference,
        Map<String, Object> order,
        Map<String, Object> postSalesCase
    ) {
        BenefitScopeType type = benefit.applicableScope().scopeType();
        Set<String> allowed = Set.copyOf(benefit.applicableScope().referenceIds());
        switch (type) {
            case ANY_TRIP -> { }
            case ORDER -> requireAllowed(allowed, useReference.orderId(), "order is outside the benefit scope");
            case POST_SALES_CASE -> {
                if (postSalesCase == null) throw precondition("postSalesCaseId is required for POST_SALES_CASE scope");
                requireAllowed(allowed, useReference.postSalesCaseId(), "post-sales case is outside the benefit scope");
            }
            case SERVICE_SEGMENT -> {
                if (useReference.segmentRefs().isEmpty()) {
                    throw precondition("segmentRefs are required for SERVICE_SEGMENT scope");
                }
                if (!allowed.containsAll(useReference.segmentRefs())) {
                    throw precondition("service segment is outside the benefit scope");
                }
            }
            case PRODUCT_CATEGORY -> {
                String offerId = text(order, "offerId", "order");
                Map<String, Object> offer = get(offers, "/api/v1/offers/{id}", offerId, "offer");
                requireEqual(offerId, text(offer, "offerId", "offer"), "offer response identity mismatch");
                requireEqual(benefit.accountId(), text(offer, "accountId", "offer"), "offer does not belong to the benefit account");
                String quoteId = text(offer, "fareQuoteRef", "offer");
                Map<String, Object> quote = get(fares, "/api/v1/fare-quotes/{id}", quoteId, "fare quote");
                requireEqual(quoteId, text(quote, "quoteId", "fare quote"), "fare quote response identity mismatch");
                requireAllowed(allowed, text(quote, "productCode", "fare quote"), "product category is outside the benefit scope");
            }
        }
    }

    private static void verifyReason(BusinessReason reason, BenefitUseReference useReference) {
        if (reason == null || reason.referenceType() == null || reason.referenceId() == null) {
            throw precondition("businessReason must identify the verified order, case, or service segment");
        }
        boolean matches = switch (reason.referenceType()) {
            case "ORDER" -> reason.referenceId().equals(useReference.orderId());
            case "POST_SALES_CASE" -> reason.referenceId().equals(useReference.postSalesCaseId());
            case "SERVICE_SEGMENT" -> useReference.segmentRefs().contains(reason.referenceId());
            default -> false;
        };
        if (!matches) throw precondition("businessReason does not match the verified useReference");
    }

    private static Map<String, Object> get(RestClient client, String path, String id, String label) {
        try {
            Map<String, Object> response = client.get().uri(path, id).retrieve().body(MAP_TYPE);
            if (response == null) throw unavailable(label + " response was empty");
            return response;
        } catch (HttpClientErrorException.NotFound exception) {
            throw precondition(label + " reference was not found");
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable(label + " could not be verified");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String field, String label) {
        Object value = source.get(field);
        if (!(value instanceof Map<?, ?> map)) throw unavailable(label + " is missing " + field);
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> source, String field, String label) {
        Object value = source.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw unavailable(label + " is missing " + field);
        return text;
    }

    private static Set<String> strings(Object value, String field) {
        if (!(value instanceof List<?> list)) throw unavailable(field + " is missing");
        Set<String> result = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) throw unavailable(field + " contains an invalid reference");
            result.add(text);
        }
        return result;
    }

    private static void requireAllowed(Set<String> allowed, String actual, String message) {
        if (!allowed.contains(actual)) throw precondition(message);
    }

    private static void requireEqual(String expected, String actual, String message) {
        if (!Objects.equals(expected, actual)) throw precondition(message);
    }

    private static ApiException precondition(String message) {
        return new ApiException(ApiErrorCode.PRECONDITION_FAILED, message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(ApiErrorCode.UNAVAILABLE, message);
    }
}
