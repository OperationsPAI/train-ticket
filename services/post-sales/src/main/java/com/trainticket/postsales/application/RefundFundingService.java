package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class RefundFundingService {
    private final PostSalesApplicationService cases;
    private final RestClient client;
    private final ObjectMapper mapper;
    public RefundFundingService(PostSalesApplicationService cases,RestClient.Builder builder,ObjectMapper mapper,
        @Value("${PAYMENT_BASE_URL:http://payment:8080}")String base){
        this.cases=cases;this.mapper=mapper;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).baseUrl(base).build();
    }
    public void prepare(String caseId,String correlation){
        var current=cases.get(caseId);
        if(current.caseType()==PostSalesCaseType.CHANGE || !current.refundPayments().isEmpty() || current.status()==PostSalesCaseStatus.EXECUTING || current.status()==PostSalesCaseStatus.APPLIED)return;
        if(current.decision()==null)current=cases.evaluate(caseId,"cmd-refund-prepare",correlation);
        Money amount=current.decision().amountSnapshot().refundAmount();if(amount.isZero())return;
        var changes=cases.appliedChanges(current.journeyOrderId());if(changes.isEmpty())return;
        Set<String> refs=new LinkedHashSet<>();
        JsonNode root=read("/api/v1/payment-intents/by-business/"+current.journeyOrderId(),correlation);refs.add(text(root,"paymentIntentId"));
        for(var change:changes){var difference=change.changeOperations().get("difference-payment");if(difference!=null)refs.add(String.valueOf(difference.get("paymentIntentId")));}
        long remaining=amount.toMinorUnits();List<Map<String,Object>> allocations=new ArrayList<>();
        for(String id:refs){
            JsonNode payment=read("/api/v1/payment-intents/"+id,correlation);
            if(!"CAPTURED".equals(text(payment,"status")))continue;
            String currency=text(payment.path("capturedAmount"),"currency");if(!amount.currency().getCurrencyCode().equals(currency))throw new DomainRuleViolation("refund funding currency mismatch");
            long available=payment.path("capturedAmount").path("minorUnits").asLong()-payment.path("refundedAmount").path("minorUnits").asLong();
            long assigned=Math.min(remaining,available);
            if(assigned>0){allocations.add(Map.of("paymentIntentId",id,"orderId",current.journeyOrderId(),"amount",Map.of("minorUnits",assigned,"currency",currency)));remaining-=assigned;}
            if(remaining==0)break;
        }
        if(remaining!=0)throw new DomainRuleViolation("available original and change payments do not cover the approved refund");
        cases.assignRefundPayments(caseId,allocations);
    }
    private JsonNode read(String path,String correlation){return client.get().uri(path).header("X-Correlation-Id",correlation==null?"":correlation).exchange((request,response)->{
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("refund payment lookup returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private static String text(JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new DomainRuleViolation("refund funding missing "+field);return value;}
}
