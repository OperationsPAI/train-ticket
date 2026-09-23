package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.ChannelRef;
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

class CaptureCallbackIdentityLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_REFUND_CALLBACK_SOURCE", matches=".+")
    void capturedCallbackReplayChecksAmountAndOriginalChannel() throws Exception {
        var mapper = new ObjectMapper();
        var payment = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPTURED_PAYMENT"))));
        var other = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REFUND_CALLBACK_SOURCE")))).path("payment");
        String schema = "capture_callback_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                String id=payment.path("paymentIntentId").asText();
                jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES (?,1,?::jsonb)",id,payment.toString());
                var service=context.getBean(PaymentCommandService.class);
                var route=payment.path("channelRef");
                String channel=route.path("channel").asText(),order=route.path("channelOrderId").asText(),transaction=route.path("channelTransactionId").asText();
                Money amount=Money.fromMinorUnits(payment.path("capturedAmount").path("minorUnits").asLong(),payment.path("capturedAmount").path("currency").asText());
                assertEquals(id,service.captureIntentFromChannel(id,amount,channel,transaction,order,"evt-repeat","corr-repeat").paymentIntentId());
                assertEquals(id,service.captureIntent(id,UUID.randomUUID().toString(),"corr-command-repeat",channel).paymentIntentId());
                assertEquals(id,service.captureIntent(id,UUID.randomUUID().toString(),"corr-command-repeat",new ChannelRef(channel,order,null,transaction,null,null,null)).paymentIntentId());
                assertThrows(DomainRuleViolation.class,()->service.captureIntent(id,UUID.randomUUID().toString(),"corr-command-other",new ChannelRef(channel,other.path("channelRef").path("channelOrderId").asText(),null,transaction,null,null,null)));
                assertThrows(DomainRuleViolation.class,()->service.captureIntent(id,UUID.randomUUID().toString(),"corr-command-other",other.path("channelRef").path("channel").asText()));
                assertThrows(DomainRuleViolation.class,()->service.captureIntentFromChannel(id,amount.add(Money.fromMinorUnits(1,"CNY")),channel,transaction,order,"evt-amount","corr-repeat"));
                assertThrows(DomainRuleViolation.class,()->service.captureIntentFromChannel(id,amount,other.path("channelRef").path("channel").asText(),transaction,order,"evt-channel","corr-repeat"));
                assertThrows(DomainRuleViolation.class,()->service.captureIntentFromChannel(id,amount,channel,other.path("channelRef").path("channelTransactionId").asText(),order,"evt-transaction","corr-repeat"));
                assertThrows(DomainRuleViolation.class,()->service.captureIntentFromChannel(id,amount,channel,transaction,other.path("channelRef").path("channelOrderId").asText(),"evt-order","corr-repeat"));
                assertEquals(1L,jdbc.queryForObject("SELECT version FROM payment_intent_snapshots WHERE id=?",Long.class,id));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                assertEquals(payment,mapper.readTree(jdbc.queryForObject("SELECT data FROM payment_intent_snapshots WHERE id=?",String.class,id)));
            } finally {
                if(context!=null)context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
