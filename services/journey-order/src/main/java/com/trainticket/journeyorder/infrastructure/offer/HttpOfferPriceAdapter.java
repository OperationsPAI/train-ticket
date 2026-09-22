package com.trainticket.journeyorder.infrastructure.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.out.OfferPricePort;
import com.trainticket.journeyorder.domain.Money;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads the requested offer version and rejects unavailable or invalid pricing. */
@Component
public class HttpOfferPriceAdapter implements OfferPricePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpOfferPriceAdapter.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpOfferPriceAdapter(ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        this(mapper, restClientBuilder, System.getenv().getOrDefault("OFFER_MANAGEMENT_URL", "http://offer-management:8080"));
    }

    HttpOfferPriceAdapter(ObjectMapper mapper, RestClient.Builder restClientBuilder, String baseUrl) {
        this.mapper = mapper;
        // Same two constraints as HttpIdentityVerificationAdapter, for the same
        // reasons: a plain HTTP/1.1 factory (the JDK client's h2c upgrade gets a
        // plain-text rejection that breaks JSON error parsing), and a builder from
        // the container, because java-kit installs the trace-propagation
        // interceptor on RestClient.Builder beans only -- a hand-built client
        // drops the outbound traceparent and starts a detached trace.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restClient = restClientBuilder.requestFactory(requestFactory).baseUrl(normalized).build();
    }

    @Override
    public Optional<Money> totalFor(String offerId, int offerVersion) {
        if (offerId == null || offerId.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "offerId is required");
        }
        try {
            // Read the raw body and parse it here rather than asking RestClient for a
            // JsonNode. Requesting JsonNode directly fails with
            // "HttpMessageConversionException: Type definition error: JsonNode" --
            // the configured Jackson converter will not target it. Verified against
            // the running service; HttpIdentityVerificationAdapter uses this same
            // exchange-and-readTree shape for the same reason.
            JsonNode offer = restClient.get()
                .uri("/api/v1/offers/{offerId}", offerId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 400) {
                        throw new ApiException(status == 404 ? ApiErrorCode.NOT_FOUND : ApiErrorCode.UNAVAILABLE,
                            "Offer lookup returned HTTP " + status);
                    }
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return body.isBlank() ? null : mapper.readTree(body);
                }, false);
            if (offer == null) {
                throw new ApiException(ApiErrorCode.UNAVAILABLE, "Offer lookup returned an empty response");
            }
            JsonNode total = offer.path("total");
            if (total.isMissingNode() || !total.hasNonNull("minorUnits") || !total.hasNonNull("currency")) {
                throw new ApiException(ApiErrorCode.UNAVAILABLE, "Offer response has no usable total");
            }
            int returnedVersion = offer.path("offerVersion").asInt(-1);
            if (returnedVersion != offerVersion) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Offer version does not match the order request");
            }
            return Optional.of(Money.fromMinorUnits(total.get("minorUnits").asLong(), total.get("currency").asText()));
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException api) throw api;
            throw new ApiException(ApiErrorCode.UNAVAILABLE, "Unable to read offer pricing", exception);
        }
    }
}
