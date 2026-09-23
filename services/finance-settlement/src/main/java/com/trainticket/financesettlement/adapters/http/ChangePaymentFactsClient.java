package com.trainticket.financesettlement.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.Money;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ChangePaymentFactsClient {
    private final RestClient client;
    private final String template;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();

    public ChangePaymentFactsClient(@org.springframework.beans.factory.annotation.Qualifier("financeRestClientBuilder") RestClient.Builder builder,
        @Value("${FINANCE_REFERENCE_URL_TEMPLATE:http://%s:8080}") String template) {
        this.template=template;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        this.client=builder.requestFactory(factory).build();
    }

    public String orderFor(String caseId,String paymentId,Money amount,String correlation) {
        JsonNode change=read("post-sales","/api/v1/post-sales-cases/"+caseId,correlation);
        if(!caseId.equals(text(change,"caseId")) || !"CHANGE".equals(text(change,"caseType")))throw new DomainRuleViolation("payment business reference is not a change case");
        if(!amount.equals(money(change.path("decision").path("amountDue"))))throw new DomainRuleViolation("change payment amount differs from approved difference");
        String orderId=text(change,"journeyOrderId");
        JsonNode order=read("journey-order","/api/v1/journey-orders/"+orderId,correlation);
        JsonNode payment=read("payment","/api/v1/payment-intents/"+paymentId,correlation);
        if(!orderId.equals(text(order,"orderId")) || !paymentId.equals(text(payment,"paymentIntentId"))
            || !caseId.equals(text(payment,"businessRef")) || !"change".equals(text(payment,"purpose"))
            || !text(order,"accountId").equals(text(payment,"payerRef")) || !amount.equals(money(payment.path("amount")))) {
            throw new DomainRuleViolation("change payment facts do not match the original order");
        }
        return orderId;
    }

    public JsonNode read(String service,String path,String correlation) {
        return client.get().uri(template.formatted(service)+path).header("X-Correlation-Id",correlation)
            .exchange((request,response)->{
                if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("financial reference lookup returned "+response.getStatusCode());
                return mapper.readTree(response.getBody());
            });
    }
    private static String text(JsonNode node,String field) {
        if(node==null || !node.path(field).isTextual() || node.path(field).asText().isBlank())throw new DomainRuleViolation("financial reference missing "+field);
        return node.path(field).asText();
    }
    private static Money money(JsonNode node) {
        if(!node.path("minorUnits").isIntegralNumber())throw new DomainRuleViolation("financial reference requires integer minor units");
        var currency=java.util.Currency.getInstance(text(node,"currency"));
        return new Money(currency,java.math.BigDecimal.valueOf(node.path("minorUnits").asLong(),currency.getDefaultFractionDigits()));
    }
}
