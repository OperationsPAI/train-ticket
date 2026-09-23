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
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentCreationReplayLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CAPTURED_PAYMENT", matches=".+")
    void concurrentCreationKeepsOneIntentAndRejectsChangedDetails() throws Exception {
        var payment = new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("TEST_CAPTURED_PAYMENT"))));
        String schema = "payment_create_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                var service = context.getBean(PaymentCommandService.class);
                String business = payment.path("businessRef").asText(), purpose = payment.path("purpose").asText(), payer = payment.path("payerRef").asText();
                String channel = payment.path("channelRef").path("channel").asText(), key = UUID.randomUUID().toString();
                Money amount = Money.fromMinorUnits(payment.path("amount").path("minorUnits").asLong(), payment.path("amount").path("currency").asText());
                try (var workers = Executors.newFixedThreadPool(8)) {
                    var calls = new ArrayList<java.util.concurrent.Future<String>>();
                    for (int index = 0; index < 16; index++) {
                        calls.add(workers.submit(() -> service.createIntent(business,purpose,amount,payer,key,"corr-replay",channel).paymentIntentId()));
                    }
                    String id = calls.getFirst().get(10, TimeUnit.SECONDS);
                    for (var call : calls) assertEquals(id, call.get(10, TimeUnit.SECONDS));
                    assertEquals(id, service.createIntent(business,purpose,amount,payer,key,"corr-new",channel).paymentIntentId());
                }
                assertThrows(DomainRuleViolation.class, () -> service.createIntent(business,purpose,amount.add(Money.fromMinorUnits(1,"CNY")),payer,key,"corr-change",channel));
                assertThrows(DomainRuleViolation.class, () -> service.createIntent(payer,purpose,amount,payer,key,"corr-change",channel));
                assertThrows(DomainRuleViolation.class, () -> service.createIntent(business,purpose,amount,business,key,"corr-change",channel));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_intent_snapshots", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
            } finally {
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
