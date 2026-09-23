package com.trainticket.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.adapters.paymentchannel.HttpPaymentChannelClient;
import com.trainticket.payment.domain.*;
import com.trainticket.payment.domain.ports.RefundRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class OrphanRefundRecoveryService {
    private final PaymentCommandService commands;
    private final RefundRepository refunds;
    private final HttpPaymentChannelClient channel;
    private final RestClient cases;
    private final ObjectMapper mapper;
    public OrphanRefundRecoveryService(PaymentCommandService commands,RefundRepository refunds,HttpPaymentChannelClient channel,
        RestClient.Builder builder,ObjectMapper mapper,@Value("${POST_SALES_BASE_URL:http://post-sales:8080}")String base) {
        this.commands=commands;this.refunds=refunds;this.channel=channel;this.mapper=mapper;
        var factory=new SimpleClientHttpRequestFactory();factory.setReadTimeout(Duration.ofSeconds(5));factory.setConnectTimeout(Duration.ofSeconds(2));
        cases=builder.requestFactory(factory).baseUrl(base).build();
    }
    @Transactional
    public Refund recover(String caseId,String channelRefundId) {
        JsonNode approved=cases.get().uri("/api/v1/post-sales-cases/"+caseId).exchange((request,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("approved case unavailable");
            return mapper.readTree(response.getBody());
        });
        if(!caseId.equals(text(approved,"caseId")) || !"APPLIED".equals(text(approved,"status"))
            || !List.of("REFUND","CANCELLATION").contains(text(approved,"caseType")))throw new DomainRuleViolation("applied refund case required");
        var facts=channel.refundFacts(channelRefundId);
        if(!channelRefundId.equals(text(facts,"channelRefundId")))throw new DomainRuleViolation("channel refund identity mismatch");
        String paymentId=text(facts,"paymentIntentId"),refundId=text(facts,"refundId");
        var intent=commands.getIntent(paymentId);var route=intent.channelRef();
        if(!facts.path("amount").path("minorUnits").isIntegralNumber() || !approved.path("decision").path("refundableAmount").path("minorUnits").isIntegralNumber())throw new DomainRuleViolation("refund recovery requires integer amounts");
        Money amount=Money.fromMinorUnits(facts.path("amount").path("minorUnits").asLong(),text(facts.path("amount"),"currency"));
        Money approvedAmount=Money.fromMinorUnits(approved.path("decision").path("refundableAmount").path("minorUnits").asLong(),text(approved.path("decision").path("refundableAmount"),"currency"));
        if(!amount.equals(approvedAmount) || !intent.businessRef().equals(text(approved,"journeyOrderId")) || intent.status()!=PaymentIntentStatus.CAPTURED
            || route==null || !Objects.equals(route.channelOrderId(),text(facts,"channelOrderId"))
            || !Objects.equals(route.channelTransactionId(),text(facts,"originalChannelTransactionId"))
            || !ChannelRouter.normalize(route.channel()).equals(ChannelRouter.normalize(text(facts,"channel"))))throw new DomainRuleViolation("orphan refund does not match approved payment and original route");
        var existing=refunds.findByBusinessCaseRef(caseId);
        if(existing.stream().anyMatch(refund->!refund.refundId().equals(refundId)))throw new DomainRuleViolation("case already has another refund requiring reconciliation");
        String key=caseId;
        Refund recovered=refunds.findById(refundId).orElse(null);
        if(recovered!=null && !recovered.semanticallyMatches(paymentId,amount,caseId,text(facts,"refundReasonCode"),key))throw new DomainRuleViolation("recorded refund belongs to another business operation");
        if(recovered==null) {
            if(!intent.refundedAmount().isZero())throw new DomainRuleViolation("payment has previous refunds requiring reconciliation");
            recovered=Refund.rehydrate(refundId,paymentId,amount,caseId,text(facts,"refundReasonCode"),key,RefundStatus.REQUESTED,null,0,List.of(),route);
            refunds.save(recovered);
        }
        String correlation=text(facts,"correlationId");
        if("SUCCEEDED".equals(text(facts,"status"))) {
            return commands.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,route.channelOrderId(),route.channel(),route.channelTransactionId(),text(facts,"channelRefundTransactionId"),"cmd-orphan-refund-recovery",correlation);
        }
        if(!"CREATED".equals(text(facts,"status")))throw new DomainRuleViolation("channel refund state requires separate reconciliation");
        var handoff=channel.resumeRefund(facts,Refund.stableId(refundId,channelRefundId,"recover-submit").substring(3),correlation,route);
        recovered.recordChannelHandoff(handoff.channelRef());
        refunds.save(recovered);
        if("SUCCEEDED".equals(handoff.status()))return commands.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,route.channelOrderId(),route.channel(),route.channelTransactionId(),handoff.channelRefundTransactionId(),"cmd-orphan-refund-recovery",correlation);
        return recovered;
    }
    private static String text(JsonNode node,String field) {
        String value=node.path(field).asText();if(value.isBlank())throw new DomainRuleViolation("recovery facts missing "+field);return value;
    }
}
