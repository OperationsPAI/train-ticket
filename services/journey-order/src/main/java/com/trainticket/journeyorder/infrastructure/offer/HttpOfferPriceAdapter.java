package com.trainticket.journeyorder.infrastructure.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.out.OfferPricePort;
import com.trainticket.journeyorder.domain.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads an offer's priced total from offer-management.
 *
 * Replaces a hardcoded `Money.of("CNY", "100.00")` in order creation. See
 * {@link OfferPricePort} for why that mattered beyond the order's own display:
 * post-sales derives the refund penalty base from the order fare, so a wrong
 * fare silently mispriced every refund.
 */
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
            return Optional.empty();
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
                        LOGGER.warn("offer-management returned HTTP {} for offer {}", status, offerId);
                        return null;
                    }
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return body.isBlank() ? null : mapper.readTree(body);
                }, false);
            if (offer == null) {
                return Optional.empty();
            }
            JsonNode total = offer.path("total");
            if (total.isMissingNode() || !total.hasNonNull("minorUnits") || !total.hasNonNull("currency")) {
                LOGGER.warn("offer {} returned no usable total; order fare will fall back. body keys={}",
                    offerId, offer.fieldNames());
                return Optional.empty();
            }
            // The offer's version is not used to select a price: GET /offers/{id}
            // returns the current snapshot, and an order is always created against
            // the offer as it stands. Logged when they disagree so a stale client
            // is visible rather than silently repriced.
            int returnedVersion = offer.path("offerVersion").asInt(offerVersion);
            if (returnedVersion != offerVersion) {
                LOGGER.info("offer {} is at version {} but the order requested {}; using the current price",
                    offerId, returnedVersion, offerVersion);
            }
            return Optional.of(Money.fromMinorUnits(total.get("minorUnits").asLong(), total.get("currency").asText()));
        } catch (RuntimeException exception) {
            // Never fail order creation on this. An unavailable offer-management
            // must not stop orders being placed, so the caller falls back to a
            // default fare -- but it is logged at WARN, because an order whose
            // fare is a placeholder is exactly the condition that made every
            // refund wrong in the first place.
            LOGGER.warn("could not read the priced total for offer {} ({}); the order's fare will be a "
                    + "placeholder, which will misprice any refund against it",
                offerId, exception.getClass().getSimpleName(), exception);
            return Optional.empty();
        }
    }
}
