package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ChangeExecutionService {
    private final PostSalesApplicationService cases;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String baseTemplate;

    public ChangeExecutionService(PostSalesApplicationService cases,RestClient.Builder builder,ObjectMapper mapper,
        @Value("${CHANGE_SERVICE_URL_TEMPLATE:http://%s:8080}") String baseTemplate) {
        this.cases=cases;this.mapper=mapper;this.baseTemplate=baseTemplate;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }

    public PostSalesCase advance(String caseId,String correlation) {
        PostSalesCase change=cases.get(caseId);
        if(change.caseType()!=PostSalesCaseType.CHANGE || change.decision()==null)throw new DomainRuleViolation("evaluated CHANGE case is required");
        if(change.status()==PostSalesCaseStatus.APPLIED || change.status()==PostSalesCaseStatus.FAILED)return change;
        if(change.status()!=PostSalesCaseStatus.EXECUTING)throw new DomainRuleViolation("change must be approved before execution");
        String id=PostSalesMapper.prefixed(change.caseId(),"psc-");
        Map<String,Map<String,Object>> ops=change.changeOperations();
        if(!ops.containsKey("target")) {
            JsonNode order=get("journey-order","/api/v1/journey-orders/"+change.journeyOrderId(),correlation);
            JsonNode offer=get("offer-management","/api/v1/offers/"+change.targetOfferId(),correlation);
            if(!order.path("accountId").asText().equals(offer.path("accountId").asText()) || !Instant.now().isBefore(Instant.parse(text(offer,"expiresAt"))))throw new DomainRuleViolation("target offer is unavailable for the original account");
            JsonNode itinerary=get("trip-planning","/api/v1/itineraries/"+text(offer,"itineraryRef"),correlation);
            JsonNode originalPayment=get("payment","/api/v1/payment-intents/by-business/"+change.journeyOrderId(),correlation);
            if(!"CAPTURED".equals(text(originalPayment,"status")) || originalPayment.path("refundedAmount").path("minorUnits").asLong()!=0)throw new DomainRuleViolation("original payment is not fully available for change");
            JsonNode fare=get("fare-pricing","/api/v1/fare-quotes/"+text(offer,"fareQuoteRef"),correlation);
            return cases.recordChangeOperation(caseId,"target",Map.of("offer",map(offer),"itinerary",map(itinerary),"fare",map(fare),"payment",map(originalPayment),"accountId",text(order,"accountId")));
        }
        JsonNode target=mapper.valueToTree(ops.get("target"));
        JsonNode offer=target.path("offer");
        for(JsonNode item:offer.path("items")) for(String traveler:change.scope().travelerRefs()) {
            String name="booking:"+item.path("segmentRef").asText()+":"+traveler;
            if(!ops.containsKey(name))return saveCommand(change,name,"booking-orchestration","/api/v1/internal/change-reservations",
                Map.of("caseId",id,"segmentRef",text(item,"segmentRef"),"travelerRef",traveler),correlation);
            String bookingId=String.valueOf(ops.get(name).get("segmentBookingId"));
            if(!ops.containsKey("confirmed:"+bookingId)) {
                JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
                if("FAILED".equals(text(booking,"status")) || "CANCELLED".equals(text(booking,"status")))throw new DomainRuleViolation("replacement reservation failed: "+bookingId);
                if(!"CONFIRMED".equals(text(booking,"status")))return change;
                JsonNode hold=getPending("capacity-availability","/api/v1/capacity-holds/"+text(booking,"capacityHoldId"),correlation);
                if(hold==null)return change;
                if(!"CONFIRMED".equals(text(hold,"status")))return change;
                return cases.recordChangeOperation(caseId,"confirmed:"+bookingId,Map.of("booking",map(booking),"hold",map(hold)));
            }
        }
        Money due=change.decision().amountSnapshot().extraChargeAmount();
        if(!due.isZero()) {
            if(!ops.containsKey("difference-payment"))return saveCommand(change,"difference-payment","payment","/api/v1/payment-intents",
                Map.of("businessRef",id,"purpose","change","payerRef",text(target,"accountId"),"amount",PostSalesMapper.money(due),"preferredChannel","ALIPAY_SIM"),correlation);
            String paymentId=String.valueOf(ops.get("difference-payment").get("paymentIntentId"));
            JsonNode currentPayment=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(Set.of("EXPIRED","CANCELLED","FAILED").contains(text(currentPayment,"status"))
                && currentPayment.path("capturedAmount").path("minorUnits").asLong()==0) {
                return compensateUnpaidChange(change,currentPayment,correlation);
            }
            if(!ops.containsKey("difference-captured"))return saveCommand(change,"difference-captured","payment","/api/v1/payment-intents/"+paymentId+"/capture",
                Map.of("channelRef",Map.of("channel","ALIPAY_SIM")),correlation);
            JsonNode paid=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(!"CAPTURED".equals(text(paid,"status")))return change;
            if(!id.equals(text(paid,"businessRef")) || due.toMinorUnits()!=paid.path("capturedAmount").path("minorUnits").asLong())throw new DomainRuleViolation("change payment does not match the quoted amount");
        }
        for(var operation:ops.entrySet())if(operation.getKey().startsWith("confirmed:")) {
            JsonNode booking=mapper.valueToTree(operation.getValue()).path("booking");
            String bookingId=text(booking,"segmentBookingId");
            if(!ops.containsKey("ticket:"+bookingId)) {
                JsonNode leg=null;
                for(JsonNode candidate:target.path("itinerary").path("legs"))if(text(booking,"segmentRef").equals(candidate.path("serviceSegmentRef").asText()))leg=candidate;
                if(leg==null)throw new DomainRuleViolation("replacement segment is outside target itinerary");
                JsonNode hold=mapper.valueToTree(operation.getValue()).path("hold");
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("segmentBookingId",bookingId);body.put("journeyOrderId",change.journeyOrderId());body.put("travelerRef",text(booking,"travelerRef"));
                body.put("segmentRef",text(booking,"segmentRef"));body.put("issuePurpose","REPLACEMENT");
                body.put("scheduledServiceRef",text(leg,"servicePlanRef"));body.put("serviceDate",Instant.parse(text(leg,"departureTime")).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString());
                body.put("capacityHoldId",text(hold,"holdId"));body.put("capacityUnitRef",text(hold,"capacityUnitRef"));body.put("interval",map(hold.path("interval")));
                body.put("classRef",text(target.path("fare").path("breakdown").path("seatClassMultiplier"),"seatClass"));body.put("expiresAt",text(leg,"departureTime"));
                return saveCommand(change,"ticket:"+bookingId,"entitlement-ticketing","/api/v1/entitlements",body,correlation);
            }
        }
        for(String ticket:change.scope().entitlementRefs()) {
            if(!ops.containsKey("void:"+ticket))return saveCommand(change,"void:"+ticket,"entitlement-ticketing","/api/v1/entitlements/"+ticket+"/void",
                Map.of("reason","CHANGE","policy","NORMAL","businessCaseRef",id),correlation);
        }
        for(String bookingId:change.scope().orderItemRefs()) {
            JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
            if(!"CANCELLED".equals(text(booking,"status")))return change;
            JsonNode hold=get("capacity-availability","/api/v1/capacity-holds/"+text(booking,"capacityHoldId"),correlation);
            if(!"RELEASED".equals(text(hold,"status")))return change;
            if(!change.releasedOrderItems().contains(bookingId))return cases.recordChangeCapacityReleased(caseId,bookingId);
        }
        Money refund=change.decision().amountSnapshot().refundAmount();
        if(!refund.isZero()) {
            if(!ops.containsKey("difference-refund"))return saveCommand(change,"difference-refund","payment","/api/v1/refunds",
                Map.of("paymentIntentId",text(target.path("payment"),"paymentIntentId"),"amount",PostSalesMapper.money(refund),"reason","CHANGE_FARE_DIFFERENCE",
                    "businessCaseRef",id,"channelRef",map(target.path("payment").path("channelRef"))),correlation);
            JsonNode returned=get("payment","/api/v1/refunds/"+ops.get("difference-refund").get("refundId"),correlation);
            if(!"SETTLED".equals(text(returned,"status")))return change;
        }
        if(!ops.containsKey("order-applied"))return saveCommand(change,"order-applied","journey-order","/api/v1/journey-orders/"+change.journeyOrderId()+"/apply-change",
            Map.of("caseId",id),correlation);
        return cases.completeChange(caseId,correlation);
    }

    private PostSalesCase compensateUnpaidChange(PostSalesCase change,JsonNode payment,String correlation) {
        var ops=change.changeOperations();
        if(ops.keySet().stream().anyMatch(name->name.startsWith("ticket:") || name.startsWith("void:") || name.equals("order-applied"))) {
            throw new DomainRuleViolation("unpaid change compensation requires original tickets to remain unchanged");
        }
        String caseId=PostSalesMapper.prefixed(change.caseId(),"psc-");
        if(!ops.containsKey("payment-terminated"))return cases.recordChangeOperation(change.caseId(),"payment-terminated",map(payment));
        for(var operation:ops.entrySet())if(operation.getKey().startsWith("booking:")) {
            String bookingId=String.valueOf(operation.getValue().get("segmentBookingId"));
            String name="cancel-replacement:"+bookingId;
            if(!ops.containsKey(name))return saveCommand(change,name,"booking-orchestration","/api/v1/internal/change-reservations/"+bookingId+"/cancel",
                Map.of("caseId",caseId),correlation);
            JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
            if(!"CANCELLED".equals(text(booking,"status")))return change;
            JsonNode hold=get("capacity-availability","/api/v1/capacity-holds/"+text(booking,"capacityHoldId"),correlation);
            if(!"RELEASED".equals(text(hold,"status")))return change;
        }
        for(String ticket:change.scope().entitlementRefs()) {
            if(!"ISSUED".equals(text(get("entitlement-ticketing","/api/v1/entitlements/"+ticket,correlation),"status"))) {
                throw new DomainRuleViolation("original ticket is unavailable after unpaid change");
            }
        }
        if(!ops.containsKey("unpaid-compensation-completed"))return cases.recordChangeOperation(change.caseId(),"unpaid-compensation-completed",Map.of("originalTicketsPreserved",true));
        return cases.failUnpaidChange(change.caseId(),correlation);
    }

    public Map<String,Object> issueAuthorization(String caseId,String bookingId,String correlation) {
        PostSalesCase change=cases.get(caseId);
        if(change.caseType()!=PostSalesCaseType.CHANGE || change.status()!=PostSalesCaseStatus.EXECUTING)throw new DomainRuleViolation("change is not executing");
        var operations=change.changeOperations();
        var recorded=operations.get("confirmed:"+bookingId);
        if(recorded==null)throw new DomainRuleViolation("replacement booking is not authorized by this change");
        JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
        if(!change.journeyOrderId().equals(text(booking,"journeyOrderId")))throw new DomainRuleViolation("replacement order mismatch");
        JsonNode target=mapper.valueToTree(operations.get("target"));
        String originalId=text(target.path("payment"),"paymentIntentId");
        JsonNode original=get("payment","/api/v1/payment-intents/"+originalId,correlation);
        if(!"CAPTURED".equals(text(original,"status")) || original.path("refundedAmount").path("minorUnits").asLong()!=0)throw new DomainRuleViolation("original funds are unavailable");
        String paymentId=originalId;
        Money due=change.decision().amountSnapshot().extraChargeAmount();
        if(!due.isZero()) {
            if(!operations.containsKey("difference-payment"))throw new DomainRuleViolation("difference payment is required");
            paymentId=String.valueOf(operations.get("difference-payment").get("paymentIntentId"));
            JsonNode payment=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(!"CAPTURED".equals(text(payment,"status")) || !PostSalesMapper.prefixed(change.caseId(),"psc-").equals(text(payment,"businessRef"))
                || payment.path("capturedAmount").path("minorUnits").asLong()!=due.toMinorUnits() || payment.path("refundedAmount").path("minorUnits").asLong()!=0)throw new DomainRuleViolation("difference payment is not captured for this change");
        }
        return Map.of("authorized",true,"caseId",PostSalesMapper.prefixed(change.caseId(),"psc-"),"journeyOrderId",change.journeyOrderId(),
            "segmentBookingId",bookingId,"segmentRef",text(booking,"segmentRef"),"travelerRef",text(booking,"travelerRef"),"paymentIntentId",paymentId);
    }

    private PostSalesCase saveCommand(PostSalesCase change,String name,String service,String path,Map<String,Object> body,String correlation) {
        JsonNode result=client.post().uri(baseTemplate.formatted(service)+path).header("Idempotency-Key",key(change.caseId()+":"+name))
            .header("X-Correlation-Id","corr-"+key(change.caseId()+":correlation")).body(body).exchange((request,response)->{
                if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change "+name+" returned "+response.getStatusCode()+": "+new String(response.getBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                return mapper.readTree(response.getBody());
            });
        return cases.recordChangeOperation(change.caseId(),name,map(result));
    }
    private JsonNode get(String service,String path,String correlation) {
        return client.get().uri(baseTemplate.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change reference lookup returned "+response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
    }
    private JsonNode getPending(String service,String path,String correlation){
        return client.get().uri(baseTemplate.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(response.getStatusCode().value()==404)return null;
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change pending reference lookup returned "+response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
    }
    private Map<String,Object> map(JsonNode node) {return mapper.convertValue(node,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private static String text(JsonNode node,String key) {String value=node.path(key).asText();if(value.isBlank())throw new DomainRuleViolation("change response missing "+key);return value;}
    public static String key(String material) {
        try {byte[] bytes=Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)),16);
            bytes[6]=(byte)((bytes[6]&15)|112);bytes[8]=(byte)((bytes[8]&63)|128);var buffer=java.nio.ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong()).toString();
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
}
