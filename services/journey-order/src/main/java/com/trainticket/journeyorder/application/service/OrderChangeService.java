package com.trainticket.journeyorder.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class OrderChangeService {
    private final JourneyOrderStateRepository repository;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String template;
    public OrderChangeService(JourneyOrderStateRepository repository,ObjectMapper mapper,RestClient.Builder builder,
        @Value("${CHANGE_SERVICE_URL_TEMPLATE:http://%s:8080}")String template){
        this.repository=repository;this.mapper=mapper;this.template=template;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }
    @Transactional
    public Map<String,Object> apply(String orderId,String caseId,String correlation){
        var stored=repository.findOrderForUpdate(orderId).orElseThrow(()->new DomainRuleViolation("original order not found"));
        JourneyOrder order=stored.order();
        if(order.timeline().stream().anyMatch(f->"ChangeApplied".equals(f.factType()) && caseId.equals(f.attributes().get("postSalesCaseId"))))return Map.of("orderId",orderId,"caseId",caseId,"status",order.state().name());
        JsonNode change=get("post-sales","/api/v1/post-sales-cases/"+caseId,correlation);
        if(!orderId.equals(text(change,"journeyOrderId")) || !"CHANGE".equals(text(change,"caseType")) || !"APPROVED".equals(text(change,"status")))throw new DomainRuleViolation("executing change required for order update");
        JsonNode operations=change.path("changeOperations"),target=operations.path("target"),offer=target.path("offer");
        if(!order.accountId().equals(text(offer,"accountId")))throw new DomainRuleViolation("target offer account mismatch");
        var travelers=order.travelers().stream().map(TravelerRef::travelerId).toList();
        Set<String> targetTravelers=new HashSet<>();offer.path("passengerMix").path("travelers").forEach(t->targetTravelers.add(text(t,"travelerId")));
        if(!targetTravelers.equals(new HashSet<>(travelers)))throw new DomainRuleViolation("change must preserve the travelers");
        var segments=new ArrayList<SegmentOrderSnapshot>();
        for(JsonNode leg:target.path("itinerary").path("legs"))segments.add(new SegmentOrderSnapshot(text(leg,"serviceSegmentRef"),text(leg,"originStopRef"),text(leg,"destinationStopRef"),text(leg,"mode").toUpperCase(Locale.ROOT),Instant.parse(text(leg,"departureTime")),Instant.parse(text(leg,"arrivalTime")),""));
        if(segments.isEmpty())throw new DomainRuleViolation("target itinerary is missing");
        var items=new ArrayList<OrderItem>();
        for(JsonNode item:offer.path("items")){
            String segment=text(item,"segmentRef"),itemId=text(item,"offerItemId");
            Money amount=money(item.path("itemPrice"));long share=amount.toMinorUnits()/travelers.size(),remainder=amount.toMinorUnits()%travelers.size();
            for(int index=0;index<travelers.size();index++){
                String traveler=travelers.get(index);
                String bookingId=text(operations.path("booking:"+segment+":"+traveler),"segmentBookingId");
                JsonNode ticket=get("entitlement-ticketing","/api/v1/entitlements/"+text(operations.path("ticket:"+bookingId),"entitlementId"),correlation);
                if(!"ISSUED".equals(text(ticket,"status")) || !orderId.equals(text(ticket,"journeyOrderId")) || !bookingId.equals(text(ticket,"segmentBookingId")) || !traveler.equals(text(ticket,"travelerRef")) || !segment.equals(text(ticket,"segmentRef")))throw new DomainRuleViolation("replacement ticket does not match the change");
                String id=itemId+":"+traveler;
                items.add(new OrderItem(id,OrderItemType.SEGMENT_FARE,"replacement fare",Money.fromMinorUnits(share+(index==0?remainder:0),amount.currency().getCurrencyCode()),itemId,List.of(new OrderLineBinding(id,traveler,segment,""))));
            }
        }
        for(JsonNode ref:change.path("scope").path("entitlementRefs"))if(!"VOIDED".equals(text(get("entitlement-ticketing","/api/v1/entitlements/"+ref.asText(),correlation),"status")))throw new DomainRuleViolation("original ticket has not been voided");
        Money fee=money(change.path("decision").path("changeAssessment").path("changeFee"));
        Money retained=Money.zero(fee.currency());
        for(OrderItem item:order.orderItems())if(!item.cancelled() && item.retainedOnChange() && !item.independentlySettled())retained=retained.plus(item.amount());
        JsonNode settlement=get("post-sales","/api/v1/post-sales-cases/"+caseId+"/settlement",correlation);
        if(!caseId.equals(text(settlement,"caseId")) || !orderId.equals(text(settlement,"journeyOrderId")))throw new DomainRuleViolation("change settlement identity mismatch");
        Money expected=money(settlement.path("balance"));
        if(!expected.equals(money(offer.path("total")).plus(fee).plus(retained)))throw new DomainRuleViolation("change funds do not cover target fare and retained fees");
        JsonNode downstream=offer.path("downstreamReference");
        order.applyChange(caseId,new OfferSnapshotRef(text(offer,"offerId"),offer.path("offerVersion").asInt(),Instant.parse(text(offer,"createdAt")),Instant.parse(text(offer,"expiresAt")),text(downstream,"priceSnapshotRef"),text(downstream,"ruleSnapshotRef")),segments,items,fee,Instant.now());
        if(!expected.equals(order.monetarySummary().payableTotal()))throw new DomainRuleViolation("replacement order total differs from its settled funds");
        repository.saveOrder(order,stored.idempotencyKey());
        return Map.of("orderId",orderId,"caseId",caseId,"status",order.state().name(),"offerId",order.offerSnapshot().offerId());
    }
    private JsonNode get(String service,String path,String correlation){return client.get().uri(template.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change reference returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private static String text(JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new DomainRuleViolation("change missing "+field);return value;}
    private static Money money(JsonNode node){if(!node.path("minorUnits").isIntegralNumber())throw new DomainRuleViolation("change money missing integer amount");return Money.fromMinorUnits(node.path("minorUnits").asLong(),text(node,"currency"));}
}
