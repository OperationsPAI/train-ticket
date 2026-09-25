package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.*;
import java.time.Duration;
import java.time.Clock;
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
    private final CompensationMismatchStore mismatches;
    private final Clock clock;

    public CompensationExecutionService(PostSalesApplicationService cases,ChangeExecutionGuard guard,RestClient.Builder builder,ObjectMapper mapper,
        CompensationMismatchStore mismatches,Clock clock,
        @Value("${COMPENSATION_SERVICE_URL_TEMPLATE:http://%s:8080}")String base){
        this.cases=cases;this.guard=guard;this.mapper=mapper;this.mismatches=mismatches;this.clock=clock;this.base=base;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }

    public PostSalesCase advance(String id,String correlation){
        return guard.execute(id,()->advanceLocked(id,correlation),()->cases.get(id));
    }

    private PostSalesCase advanceLocked(String id,String correlation){
        mismatches.find(id).ifPresent(checkpoint->{throw new CompensationMismatchException(checkpoint);});
        var current=cases.get(id);
        require(current.caseType()==PostSalesCaseType.COMPENSATION && current.decision()!=null,"evaluated compensation case is required");
        if(current.status()==PostSalesCaseStatus.FAILED)return current;
        boolean applied=current.status()==PostSalesCaseStatus.APPLIED;
        require(applied || current.status()==PostSalesCaseStatus.EXECUTING,"compensation must be approved");
        if(!applied)require(current.executionPlan().stream().anyMatch(step->step.type()==PostSalesStepType.ISSUE_COMPENSATION_CREDIT),"compensation requires an approved wallet credit execution plan");
        String caseId=PostSalesMapper.prefixed(current.caseId(),"psc-");
        var operations=current.compensationOperations();
        Money amount=current.decision().amountSnapshot().refundAmount();
        var rights=mapper.createObjectNode();
        rights.put("releasedOrderItems",current.releasedOrderItems().size());
        if(!current.releasedOrderItems().isEmpty())freeze(current,correlation,"ORIGINAL_RIGHTS_RELEASED","compensation cannot coexist with released original capacity",mapper.createObjectNode(),mapper.createObjectNode(),rights);
        JsonNode order=read("journey-order","/api/v1/journey-orders/"+current.journeyOrderId(),correlation);
        rights.set("order",order);
        if(!current.journeyOrderId().equals(order.path("orderId").asText("")))freeze(current,correlation,"ORDER_ID_MISMATCH","compensation order differs",mapper.createObjectNode(),mapper.createObjectNode(),rights);
        if(!order.path("status").isTextual() || Set.of("CANCELLED","REFUNDED","FAILED").contains(order.path("status").asText()))freeze(current,correlation,"ORIGINAL_ORDER_UNAVAILABLE","compensation requires preserved original order",mapper.createObjectNode(),mapper.createObjectNode(),rights);
        var tickets=rights.putArray("tickets");
        for(String ticketId:current.scope().entitlementRefs()){
            JsonNode ticket=read("entitlement-ticketing","/api/v1/entitlements/"+ticketId,correlation);
            tickets.add(ticket);
            if(!current.journeyOrderId().equals(ticket.path("journeyOrderId").asText("")) || !ticket.path("status").isTextual() || "VOIDED".equals(ticket.path("status").asText()))freeze(current,correlation,"ORIGINAL_TICKET_UNAVAILABLE","original ticket is unavailable for wallet compensation",mapper.createObjectNode(),mapper.createObjectNode(),rights);
        }
        JsonNode refunds=read("payment","/api/v1/refunds?businessCaseRef="+caseId,correlation);
        if(!refunds.path("total").isIntegralNumber() || refunds.path("total").asInt()!=0)freeze(current,correlation,"REFUND_AND_WALLET_CONFLICT","wallet compensation cannot also refund the case payment",refunds,mapper.createObjectNode(),rights);
        JsonNode payment=read("payment","/api/v1/payment-intents/by-business/"+current.journeyOrderId(),correlation);
        if(!current.journeyOrderId().equals(payment.path("businessRef").asText("")) || !"CAPTURED".equals(payment.path("status").asText(""))
            || !moneyEquals(payment.path("refundedAmount"),Money.zero(amount.currency())))freeze(current,correlation,"ORIGINAL_PAYMENT_UNAVAILABLE","wallet compensation requires preserved original payment",refunds,mapper.createObjectNode(),rights);
        if(!operations.containsKey("request")){
            if(applied)freeze(current,correlation,"LOCAL_REQUEST_EVIDENCE_MISSING","applied compensation has no persisted issuance request",refunds,mapper.createObjectNode(),rights);
            Instant now=clock.instant();var body=new LinkedHashMap<String,Object>();
            body.put("accountId",text(order,"accountId"));body.put("benefitType","COMPENSATION_CREDIT");body.put("balanceType","PROMOTION_CREDIT");
            body.put("amount",PostSalesMapper.money(amount));body.put("issuanceSource","POST_SALES_COMP");body.put("caseId",caseId);
            body.put("applicableScope",Map.of("scopeType","ANY_TRIP","currency",amount.currency().getCurrencyCode()));
            body.put("redemptionRule",Map.of("singleUse",false,"requiresReservation",false));body.put("revocationRule",Map.of());
            body.put("validFrom",now.toString());body.put("validUntil",now.plus(Duration.ofDays(30)).toString());
            body.put("businessReason",Map.of("reasonType","POST_SALES_COMP","reasonCode",current.reasonCode(),"referenceType","POST_SALES_CASE","referenceId",caseId));
            return cases.recordCompensationOperation(id,"request",body);
        }
        JsonNode request=mapper.valueToTree(operations.get("request"));
        if(!order.path("accountId").asText("").equals(request.path("accountId").asText("")) || !moneyEquals(request.path("amount"),amount))freeze(current,correlation,"REQUEST_MISMATCH","compensation request differs from approved recipient or amount",refunds,mapper.createObjectNode(),rights);
        JsonNode canonical=readOptional("wallet-promotion","/api/v1/internal/benefits/by-case/"+caseId,correlation);
        if(canonical!=null){
            verifyBenefitOrFreeze(current,canonical,request,amount,caseId,correlation,refunds,rights);
            if(operations.containsKey("issued") && !String.valueOf(operations.get("issued").get("benefitId")).equals(canonical.path("benefitId").asText("")))freeze(current,correlation,"BENEFIT_ID_MISMATCH","persisted compensation benefit differs from wallet case identity",refunds,canonical,rights);
            if(!operations.containsKey("issued")){
                if(applied)freeze(current,correlation,"LOCAL_ISSUANCE_EVIDENCE_MISSING","applied compensation has wallet credit but no persisted issuance evidence",refunds,canonical,rights);
                return cases.recordCompensationOperation(id,"issued",map(canonical));
            }
        }
        if(!amount.isZero() && !operations.containsKey("issued")){
            if(applied)freeze(current,correlation,"WALLET_CREDIT_MISSING","applied compensation has no wallet credit",refunds,mapper.createObjectNode(),rights);
            JsonNode issued=client.post().uri(base.formatted("wallet-promotion")+"/api/v1/benefits")
                .header("Idempotency-Key",ChangeExecutionService.key(caseId+":wallet-compensation"))
                .header("X-Correlation-Id",correlation).body(operations.get("request")).exchange((outgoing,response)->{
                    if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("wallet compensation returned "+response.getStatusCode());
                    return mapper.readTree(response.getBody());
                });
            verifyBenefitOrFreeze(current,issued,request,amount,caseId,correlation,refunds,rights);
            return cases.recordCompensationOperation(id,"issued",map(issued));
        }
        if(!amount.isZero()){
            JsonNode issued=readOptional("wallet-promotion","/api/v1/benefits/"+operations.get("issued").get("benefitId"),correlation);
            if(issued==null)freeze(current,correlation,"WALLET_CREDIT_MISSING","persisted compensation benefit is missing from wallet",refunds,mapper.createObjectNode(),rights);
            verifyBenefitOrFreeze(current,issued,request,amount,caseId,correlation,refunds,rights);
        }
        if(applied && !operations.containsKey("verified"))freeze(current,correlation,"LOCAL_VERIFICATION_EVIDENCE_MISSING","applied compensation has no persisted verification evidence",refunds,canonical==null?mapper.createObjectNode():canonical,rights);
        if(applied)return current;
        if(!operations.containsKey("verified"))return cases.recordCompensationOperation(id,"verified",Map.of("originalTicketsPreserved",true,"originalPaymentPreserved",true,
            "paymentIntentId",text(payment,"paymentIntentId"),"capturedAmount",map(payment.path("capturedAmount")),
            "amount",PostSalesMapper.money(amount),"benefitId",amount.isZero()?"":String.valueOf(operations.get("issued").get("benefitId"))));
        return cases.completeCompensation(id,correlation);
    }

    private void verifyBenefitOrFreeze(PostSalesCase current,JsonNode benefit,JsonNode request,Money amount,String caseId,String correlation,JsonNode refunds,JsonNode rights){
        if(benefit==null || benefit.path("benefitId").asText("").isBlank()
            || !request.path("accountId").asText("").equals(benefit.path("accountId").asText(""))
            || !caseId.equals(benefit.path("caseId").asText(""))
            || !"POST_SALES_COMP".equals(benefit.path("issuanceSource").asText(""))
            || !"COMPENSATION_CREDIT".equals(benefit.path("benefitType").asText(""))
            || !"PROMOTION_CREDIT".equals(benefit.path("balanceType").asText(""))
            || !moneyEquals(benefit.path("issuedAmount"),amount))freeze(current,correlation,"WALLET_CREDIT_MISMATCH","issued compensation differs from approved case",refunds,benefit==null?mapper.createObjectNode():benefit,rights);
    }
    private JsonNode read(String service,String path,String correlation){return client.get().uri(base.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("compensation reference "+service+" returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private JsonNode readOptional(String service,String path,String correlation){return client.get().uri(base.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
        if(response.getStatusCode().value()==404)return null;
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("compensation reference "+service+" returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private void freeze(PostSalesCase current,String correlation,String code,String reason,JsonNode refunds,JsonNode wallet,JsonNode rights){
        String stableCorrelation=correlation==null || correlation.isBlank()?"corr-unknown":correlation;
        Money approved=current.decision().amountSnapshot().refundAmount();
        var checkpoint=new CompensationMismatchCheckpoint(current.caseId(),current.journeyOrderId(),current.status().name(),code,reason,
            approved.currency().getCurrencyCode(),approved.toMinorUnits(),refunds.deepCopy(),wallet.deepCopy(),rights.deepCopy(),
            mapper.valueToTree(current.compensationOperations()),stableCorrelation,clock.instant());
        throw new CompensationMismatchException(mismatches.record(checkpoint));
    }
    private static boolean moneyEquals(JsonNode node,Money expected){
        try{return expected.equals(RefundFundingService.money(node));}catch(RuntimeException ignored){return false;}
    }
    private Map<String,Object> map(JsonNode value){return mapper.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private static String text(JsonNode value,String field){String result=value.path(field).asText();require(!result.isBlank(),"compensation response missing "+field);return result;}
    private static void require(boolean value,String message){if(!value)throw new DomainRuleViolation(message);}
}
