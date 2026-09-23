package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public final class CompensationExecutionService {
    private final PostSalesApplicationService cases;
    private final ChangeExecutionGuard guard;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String base;

    public CompensationExecutionService(PostSalesApplicationService cases,ChangeExecutionGuard guard,RestClient.Builder builder,ObjectMapper mapper,
        @Value("${COMPENSATION_SERVICE_URL_TEMPLATE:http://%s:8080}")String base){
        this.cases=cases;this.guard=guard;this.mapper=mapper;this.base=base;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }

    public PostSalesCase advance(String id,String correlation){
        return guard.execute(id,()->advanceLocked(id,correlation),()->cases.get(id));
    }

    private PostSalesCase advanceLocked(String id,String correlation){
        var current=cases.get(id);
        require(current.caseType()==PostSalesCaseType.COMPENSATION && current.decision()!=null,"evaluated compensation case is required");
        if(current.status()==PostSalesCaseStatus.APPLIED || current.status()==PostSalesCaseStatus.FAILED)return current;
        require(current.status()==PostSalesCaseStatus.EXECUTING,"compensation must be approved");
        require(current.executionPlan().stream().anyMatch(step->step.type()==PostSalesStepType.ISSUE_COMPENSATION_CREDIT),"compensation requires an approved wallet credit execution plan");
        require(current.releasedOrderItems().isEmpty(),"compensation cannot issue credit after original capacity release");
        String caseId=PostSalesMapper.prefixed(current.caseId(),"psc-");
        var operations=current.compensationOperations();
        Money amount=current.decision().amountSnapshot().refundAmount();
        JsonNode order=read("journey-order","/api/v1/journey-orders/"+current.journeyOrderId(),correlation);
        require(current.journeyOrderId().equals(text(order,"orderId")),"compensation order differs");
        require(!Set.of("CANCELLED","REFUNDED","FAILED").contains(text(order,"status")),"compensation requires preserved original order");
        for(String ticketId:current.scope().entitlementRefs()){
            JsonNode ticket=read("entitlement-ticketing","/api/v1/entitlements/"+ticketId,correlation);
            require(current.journeyOrderId().equals(text(ticket,"journeyOrderId")) && !"VOIDED".equals(text(ticket,"status")),"original ticket is unavailable for wallet compensation");
        }
        JsonNode refunds=read("payment","/api/v1/refunds?businessCaseRef="+caseId,correlation);
        require(refunds.path("total").isIntegralNumber() && refunds.path("total").asInt()==0,"wallet compensation cannot also refund the case payment");
        JsonNode payment=read("payment","/api/v1/payment-intents/by-business/"+current.journeyOrderId(),correlation);
        require(current.journeyOrderId().equals(text(payment,"businessRef")) && "CAPTURED".equals(text(payment,"status"))
            && RefundFundingService.money(payment.path("refundedAmount")).isZero(),"wallet compensation requires preserved original payment");
        if(!operations.containsKey("request")){
            Instant now=Instant.now();var body=new LinkedHashMap<String,Object>();
            body.put("accountId",text(order,"accountId"));body.put("benefitType","COMPENSATION_CREDIT");body.put("balanceType","PROMOTION_CREDIT");
            body.put("amount",PostSalesMapper.money(amount));body.put("issuanceSource","POST_SALES_COMP");body.put("caseId",caseId);
            body.put("applicableScope",Map.of("scopeType","ANY_TRIP","currency",amount.currency().getCurrencyCode()));
            body.put("redemptionRule",Map.of("singleUse",false,"requiresReservation",false));body.put("revocationRule",Map.of());
            body.put("validFrom",now.toString());body.put("validUntil",now.plus(Duration.ofDays(30)).toString());
            body.put("businessReason",Map.of("reasonType","POST_SALES_COMP","reasonCode",current.reasonCode(),"referenceType","POST_SALES_CASE","referenceId",caseId));
            return cases.recordCompensationOperation(id,"request",body);
        }
        JsonNode request=mapper.valueToTree(operations.get("request"));
        require(text(order,"accountId").equals(text(request,"accountId")) && amount.equals(RefundFundingService.money(request.path("amount"))),"compensation request differs from approved recipient or amount");
        if(!amount.isZero() && !operations.containsKey("issued")){
            JsonNode issued=client.post().uri(base.formatted("wallet-promotion")+"/api/v1/benefits")
                .header("Idempotency-Key",ChangeExecutionService.key(caseId+":wallet-compensation"))
                .header("X-Correlation-Id",correlation).body(operations.get("request")).exchange((outgoing,response)->{
                    if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("wallet compensation returned "+response.getStatusCode());
                    return mapper.readTree(response.getBody());
                });
            verifyBenefit(issued,request,amount,caseId);
            return cases.recordCompensationOperation(id,"issued",map(issued));
        }
        if(!amount.isZero()){
            JsonNode issued=read("wallet-promotion","/api/v1/benefits/"+operations.get("issued").get("benefitId"),correlation);
            verifyBenefit(issued,request,amount,caseId);
        }
        if(!operations.containsKey("verified"))return cases.recordCompensationOperation(id,"verified",Map.of("originalTicketsPreserved",true,"originalPaymentPreserved",true,
            "paymentIntentId",text(payment,"paymentIntentId"),"capturedAmount",map(payment.path("capturedAmount")),
            "amount",PostSalesMapper.money(amount),"benefitId",amount.isZero()?"":String.valueOf(operations.get("issued").get("benefitId"))));
        return cases.completeCompensation(id,correlation);
    }

    private void verifyBenefit(JsonNode benefit,JsonNode request,Money amount,String caseId){
        require(!text(benefit,"benefitId").isBlank() && text(request,"accountId").equals(text(benefit,"accountId")) && caseId.equals(text(benefit,"caseId"))
            && "POST_SALES_COMP".equals(text(benefit,"issuanceSource")) && "COMPENSATION_CREDIT".equals(text(benefit,"benefitType"))
            && "PROMOTION_CREDIT".equals(text(benefit,"balanceType")) && amount.equals(RefundFundingService.money(benefit.path("issuedAmount"))),"issued compensation differs from approved case");
    }
    private JsonNode read(String service,String path,String correlation){return client.get().uri(base.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("compensation reference "+service+" returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private Map<String,Object> map(JsonNode value){return mapper.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private static String text(JsonNode value,String field){String result=value.path(field).asText();require(!result.isBlank(),"compensation response missing "+field);return result;}
    private static void require(boolean value,String message){if(!value)throw new DomainRuleViolation(message);}
}
