package com.trainticket.journeyorder.infrastructure.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.out.OfferPricePort;
import com.trainticket.journeyorder.application.port.out.ResolvedOrderOffer;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.domain.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final RestClient itineraryClient;
    private final RestClient fareClient;

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
        this.itineraryClient = restClientBuilder.clone().requestFactory(requestFactory)
            .baseUrl(System.getenv().getOrDefault("TRIP_PLANNING_URL", "http://trip-planning:8080")).build();
        this.fareClient = restClientBuilder.clone().requestFactory(requestFactory)
            .baseUrl(System.getenv().getOrDefault("FARE_PRICING_URL", "http://fare-pricing:8080")).build();
    }

    private JsonNode read(RestClient client, String path, String id) {
        return client.get().uri(path,id).exchange((request,response) -> {
            int status=response.getStatusCode().value();
            if (status >= 400) throw new ApiException(status==404?ApiErrorCode.NOT_FOUND:ApiErrorCode.UNAVAILABLE,"Snapshot lookup returned HTTP "+status);
            return mapper.readTree(response.getBody());
        });
    }

    private static String text(JsonNode node,String key) {
        String value=node.path(key).asText("");
        if(value.isBlank()) throw new ApiException(ApiErrorCode.UNAVAILABLE,"Snapshot field missing: "+key);
        return value;
    }

    private static Money money(JsonNode node) {
        if(!node.path("minorUnits").isIntegralNumber()) throw new ApiException(ApiErrorCode.UNAVAILABLE,"Snapshot money must contain integer minorUnits");
        return Money.fromMinorUnits(node.path("minorUnits").longValue(),text(node,"currency"));
    }

    @Override
    public ResolvedOrderOffer resolve(JourneyOrderRequest request) {
        try {
            JsonNode offer=read(restClient,"/api/v1/offers/{id}",request.offerId());
            if(!request.offerId().equals(text(offer,"offerId")) || request.offerVersion()!=offer.path("offerVersion").asInt(-1)
                || !request.accountId().equals(text(offer,"accountId"))) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Offer identity, version or account differs from order request");
            }
            if(!"Quoted".equalsIgnoreCase(text(offer,"status"))) throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Offer is not available for order creation");
            JsonNode refs=offer.path("downstreamReference");
            var snapshot=new OfferSnapshotRef(request.offerId(),request.offerVersion(),Instant.parse(text(offer,"createdAt")),
                Instant.parse(text(offer,"expiresAt")),text(refs,"priceSnapshotRef"),text(refs,"ruleSnapshotRef"));
            snapshot.requireValidAt(Instant.now());
            List<TravelerRef> travelers=new ArrayList<>();
            for(JsonNode traveler:offer.path("passengerMix").path("travelers")) {
                travelers.add(new TravelerRef(text(traveler,"travelerId"),text(traveler,"travelerType")));
            }
            var travelerIds=travelers.stream().map(TravelerRef::travelerId).toList();
            if(travelers.isEmpty() || travelerIds.size()!=request.travelerRefs().size() || !new HashSet<>(travelerIds).equals(new HashSet<>(request.travelerRefs()))) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Order travelers differ from offer travelers");
            }
            JsonNode itinerary=read(itineraryClient,"/api/v1/itineraries/{id}",text(offer,"itineraryRef"));
            List<SegmentOrderSnapshot> segments=new ArrayList<>();
            for(JsonNode leg:itinerary.path("legs")) {
                segments.add(new SegmentOrderSnapshot(text(leg,"serviceSegmentRef"),text(leg,"originStopRef"),text(leg,"destinationStopRef"),
                    text(leg,"mode").toUpperCase(Locale.ROOT),Instant.parse(text(leg,"departureTime")),Instant.parse(text(leg,"arrivalTime")),""));
            }
            var segmentIds=segments.stream().map(SegmentOrderSnapshot::segmentRef).toList();
            if(segments.isEmpty() || segmentIds.size()!=request.segmentRefs().size() || !new HashSet<>(segmentIds).equals(new HashSet<>(request.segmentRefs()))) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Order segments differ from offer itinerary");
            }
            if(request.journeyDate()!=null && !request.journeyDate().isBlank()
                && !segments.getFirst().departureTime().atOffset(ZoneOffset.UTC).toLocalDate().toString().equals(request.journeyDate())) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Journey date differs from offer itinerary");
            }
            List<OrderItem> items=new ArrayList<>();
            for(JsonNode item:offer.path("items")) {
                String itemId=text(item,"offerItemId"),segment=text(item,"segmentRef");
                if(!segmentIds.contains(segment)) throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Offer item is outside the itinerary");
                Money price=money(item.path("itemPrice"));
                long share=price.toMinorUnits()/travelers.size(), remainder=price.toMinorUnits()%travelers.size();
                for(int index=0;index<travelers.size();index++) {
                    String id=itemId+":"+travelers.get(index).travelerId();
                    items.add(new OrderItem(id,OrderItemType.SEGMENT_FARE,"fare",Money.fromMinorUnits(share+(index==0?remainder:0),price.currency().getCurrencyCode()),
                        itemId,List.of(new OrderLineBinding(id,travelers.get(index).travelerId(),segment,""))));
                }
            }
            if(!MonetarySummary.fromItems(items).payableTotal().equals(money(offer.path("total")))) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Offer item prices do not equal the offered total");
            }
            String fareId = text(offer,"fareQuoteRef");
            JsonNode fare = read(fareClient,"/api/v1/fare-quotes/{id}",fareId);
            if (!fareId.equals(text(fare,"quoteId")) || !"QUOTED".equals(text(fare,"status"))
                || !money(fare.path("breakdown").path("total")).equals(money(offer.path("total")))) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Fare quote differs from the accepted offer");
            }
            if (!fare.path("eligibilityUsages").isArray()) throw new ApiException(ApiErrorCode.UNAVAILABLE,"Fare quote is missing eligibility usage details");
            List<ResolvedOrderOffer.EligibilityUsage> eligibilityUsages = new ArrayList<>();
            for (JsonNode usage : fare.path("eligibilityUsages")) {
                String traveler = text(usage,"travelerId");
                if (!travelerIds.contains(traveler)) throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Fare eligibility belongs to another traveler");
                eligibilityUsages.add(new ResolvedOrderOffer.EligibilityUsage(traveler,text(usage,"eligibilityType"),text(usage,"eligibilityCertificateId")));
            }
            var discountTypes = new HashSet<String>();
            for (JsonNode discount : fare.path("breakdown").path("discounts")) {
                String type = discount.path("explanation").path("parameters").path("eligibilityType").asText("");
                if (!type.isBlank() && discount.path("amount").path("minorUnits").asLong() > 0) discountTypes.add(type.toUpperCase(Locale.ROOT));
            }
            var usageTypes = new HashSet<>(eligibilityUsages.stream().map(ResolvedOrderOffer.EligibilityUsage::eligibilityType).toList());
            if (!discountTypes.equals(usageTypes)) throw new ApiException(ApiErrorCode.PRECONDITION_FAILED,"Fare discount has incomplete eligibility usage details");
            return new ResolvedOrderOffer(request.accountId(),text(offer,"channelId"),snapshot,travelers,segments,items,text(fare,"productCode"),eligibilityUsages);
        } catch(ApiException e) { throw e; }
        catch(RuntimeException e) {throw new ApiException(ApiErrorCode.UNAVAILABLE,"Unable to resolve authoritative order snapshot",e);}
    }

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
