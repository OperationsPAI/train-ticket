package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class RefundCallbackIdentityLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_REFUND_CALLBACK_SOURCE", matches=".+")
    void settledRefundReplayChecksAmountPaymentAndChannelIdentity() throws Exception {
        var mapper = new ObjectMapper();
        var source = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REFUND_CALLBACK_SOURCE"))));
        var refund = source.path("refund");
        var payment = source.path("payment");
        var other = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPTURED_PAYMENT"))));
        String schema = "refund_callback_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                for (var intent : new com.fasterxml.jackson.databind.JsonNode[]{payment,other}) {
                    jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES (?,1,?::jsonb)",intent.path("paymentIntentId").asText(),intent.toString());
                }
                jdbc.update("INSERT INTO refund_snapshots(id,version,data) VALUES (?,1,?::jsonb)",refund.path("refundId").asText(),refund.toString());
                var service = context.getBean(PaymentCommandService.class);
                String refundId=refund.path("refundId").asText(),paymentId=payment.path("paymentIntentId").asText();
                var route=refund.path("channelRef");
                String channel=route.path("channel").asText(),orderId=route.path("channelOrderId").asText(),channelRefundId=route.path("channelRefundId").asText();
                String originalTransaction=route.path("channelTransactionId").asText(),refundTransaction=route.path("channelRefundTransactionId").asText();
                Money amount=Money.fromMinorUnits(refund.path("amount").path("minorUnits").asLong(),refund.path("amount").path("currency").asText());
                assertEquals(refundId,service.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,orderId,channel,originalTransaction,refundTransaction,"evt-replay","corr-replay").refundId());
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,other.path("paymentIntentId").asText(),amount,channelRefundId,orderId,channel,originalTransaction,refundTransaction,"evt-other","corr-other"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,paymentId,amount.add(Money.fromMinorUnits(1,"CNY")),channelRefundId,orderId,channel,originalTransaction,refundTransaction,"evt-amount","corr-amount"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,other.path("channelRef").path("channelOrderId").asText(),channel,originalTransaction,refundTransaction,"evt-order","corr-order"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,orderId,other.path("channelRef").path("channel").asText(),originalTransaction,refundTransaction,"evt-channel","corr-channel"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,orderId,channel,other.path("channelRef").path("channelTransactionId").asText(),refundTransaction,"evt-original","corr-original"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(refundId,paymentId,amount,channelRefundId,orderId,channel,originalTransaction,originalTransaction,"evt-refund","corr-refund"));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                assertEquals(1L,jdbc.queryForObject("SELECT version FROM refund_snapshots WHERE id=?",Long.class,refundId));
                assertEquals(payment.path("refundedAmount"),mapper.readTree(jdbc.queryForObject("SELECT data->'refundedAmount' FROM payment_intent_snapshots WHERE id=?",String.class,paymentId)));
                var pending=service.requestRefund(other.path("paymentIntentId").asText(),amount,refund.path("reasonCode").asText(),refund.path("sourceCaseRef").asText(),UUID.randomUUID().toString(),"corr-pending");
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(pending.refundId(),paymentId,amount,channelRefundId,orderId,channel,originalTransaction,refundTransaction,"evt-wrong-payment","corr-pending"));
                assertThrows(DomainRuleViolation.class,()->service.settleRefundFromChannel(pending.refundId(),other.path("paymentIntentId").asText(),amount,channelRefundId,orderId,channel,originalTransaction,refundTransaction,"evt-wrong-route","corr-pending"));
                assertEquals("REQUESTED",jdbc.queryForObject("SELECT data->>'status' FROM refund_snapshots WHERE id=?",String.class,pending.refundId()));
                assertEquals(other.path("refundedAmount"),mapper.readTree(jdbc.queryForObject("SELECT data->'refundedAmount' FROM payment_intent_snapshots WHERE id=?",String.class,other.path("paymentIntentId").asText())));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RefundSettled'",Integer.class));
            } finally {
                if(context!=null)context.close();
                admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
}
