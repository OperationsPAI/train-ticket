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
        // A case that has reached EXECUTING without any refund payments was never
        // funded -- the funding step runs from the approval endpoint, and a case
        // that got past it without funding has no other path forward. Measured:
        // 58 REFUND cases sit in EXECUTING with no payments, and the load
        // generator retries the ones it holds, 30 times each in one window.
        // Funding them is the only way that state resolves, so an unfunded
        // EXECUTING case is treated as unfinished rather than done.
        boolean funded = !current.refundPayments().isEmpty();
        if(current.caseType()==PostSalesCaseType.CHANGE || current.caseType()==PostSalesCaseType.COMPENSATION
            || funded || current.status()==PostSalesCaseStatus.APPLIED)return;
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
    public List<JsonNode> changePayments(PostSalesCase current,String payer,boolean includeCurrent,String correlation) {
        Map<String,String> sources=new LinkedHashMap<>();
        JsonNode root=read("/api/v1/payment-intents/by-business/"+current.journeyOrderId(),correlation);
        sources.put(text(root,"paymentIntentId"),current.journeyOrderId());
        var changes=new ArrayList<>(cases.appliedChanges(current.journeyOrderId()));
        changes.sort(Comparator.comparing(PostSalesCase::createdAt));
        if(includeCurrent && changes.stream().noneMatch(change->change.caseId().equals(current.caseId())))changes.add(current);
        Map<String,Money> expected=new HashMap<>();
        for(var change:changes) {
            Money due=change.decision().amountSnapshot().extraChargeAmount();
            if(due.isZero())continue;
            var difference=change.changeOperations().get("difference-payment");
            if(difference==null)throw new DomainRuleViolation("change funding requires its recorded payment");
            String id=String.valueOf(difference.get("paymentIntentId"));
            if(sources.putIfAbsent(id,PostSalesMapper.prefixed(change.caseId(),"psc-"))!=null)throw new DomainRuleViolation("change payment is assigned more than once");
            expected.put(id,due);
        }
        List<JsonNode> payments=new ArrayList<>();
        for(var source:sources.entrySet()) {
            JsonNode payment=source.getKey().equals(text(root,"paymentIntentId"))?root:read("/api/v1/payment-intents/"+source.getKey(),correlation);
            String purpose=expected.containsKey(source.getKey())?"change":"purchase";
            if(!source.getValue().equals(text(payment,"businessRef")) || !payer.equals(text(payment,"payerRef"))
                || !purpose.equals(text(payment,"purpose")) || !"CAPTURED".equals(text(payment,"status")))throw new DomainRuleViolation("change funding payment identity mismatch");
            Money captured=money(payment.path("capturedAmount")),returned=money(payment.path("refundedAmount"));
            if(returned.compareTo(captured)>0 || !captured.equals(money(payment.path("amount"))))throw new DomainRuleViolation("change funding payment amount mismatch");
            if(expected.containsKey(source.getKey()) && !expected.get(source.getKey()).equals(captured))throw new DomainRuleViolation("change payment differs from its approved amount");
            if(PostSalesMapper.prefixed(current.caseId(),"psc-").equals(source.getValue()) && !returned.isZero())throw new DomainRuleViolation("current change payment has already been refunded");
            payments.add(payment);
        }
        return List.copyOf(payments);
    }
    public static Money balance(List<JsonNode> payments) {
        if(payments.isEmpty())throw new DomainRuleViolation("change funding requires payments");
        Money total=Money.zero(money(payments.getFirst().path("capturedAmount")).currency());
        for(JsonNode payment:payments) {
            Money captured=money(payment.path("capturedAmount")),returned=money(payment.path("refundedAmount"));
            returned.compareTo(captured);
            total=total.add(Money.fromMinorUnits(Math.subtractExact(captured.toMinorUnits(),returned.toMinorUnits()),captured.currency().getCurrencyCode()));
        }
        return total;
    }
    public static Money money(JsonNode node) {
        if(!node.path("minorUnits").isIntegralNumber())throw new DomainRuleViolation("funding amount must contain integer minorUnits");
        return Money.fromMinorUnits(node.path("minorUnits").asLong(),text(node,"currency"));
    }
    private JsonNode read(String path,String correlation){return client.get().uri(path).header("X-Correlation-Id",correlation==null?"":correlation).exchange((request,response)->{
        if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("refund payment lookup returned "+response.getStatusCode());return mapper.readTree(response.getBody());});}
    private static String text(JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new DomainRuleViolation("refund funding missing "+field);return value;}
}
