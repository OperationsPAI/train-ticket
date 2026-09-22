package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentConcurrencyLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_REFUND_CALLBACK_SOURCE", matches=".+")
    void paymentWritersSerializeAndRefundCallbackWaitsForRequestCommit() throws Exception {
        var mapper = new ObjectMapper();
        var source = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REFUND_CALLBACK_SOURCE"))));
        var payment = source.path("payment");
        var refund = source.path("refund");
        String paymentId = payment.path("paymentIntentId").asText();
        String refundId = refund.path("refundId").asText();
        String schema = "payment_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                var repository = context.getBean(PaymentIntentRepository.class);
                var service = context.getBean(PaymentCommandService.class);
                jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES (?,1,?::jsonb)", paymentId, payment.toString());
                try (var workers = Executors.newFixedThreadPool(8)) {
                    var updates = new ArrayList<java.util.concurrent.Future<?>>();
                    for (int index = 0; index < 32; index++) {
                        updates.add(workers.submit(() -> transaction.executeWithoutResult(status -> {
                            var intent = repository.findById(paymentId).orElseThrow();
                            repository.save(intent);
                        })));
                    }
                    for (var update : updates) update.get(10, TimeUnit.SECONDS);
                    assertEquals(33L, jdbc.queryForObject("SELECT version FROM payment_intent_snapshots WHERE id=?", Long.class, paymentId));

                    var inserted = new CountDownLatch(1);
                    var commit = new CountDownLatch(1);
                    var writer = workers.submit(() -> transaction.executeWithoutResult(status -> {
                        repository.findById(paymentId).orElseThrow();
                        jdbc.update("INSERT INTO refund_snapshots(id,version,data) VALUES (?,1,?::jsonb)", refundId, refund.toString());
                        inserted.countDown();
                        try {
                            assertTrue(commit.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
                    try {
                        assertTrue(inserted.await(10, TimeUnit.SECONDS));
                        var route = refund.path("channelRef");
                        var started = new CountDownLatch(1);
                        var callback = workers.submit(() -> {
                            started.countDown();
                            return service.settleRefundFromChannel(refundId, paymentId,
                                Money.fromMinorUnits(refund.path("amount").path("minorUnits").asLong(), refund.path("amount").path("currency").asText()),
                                route.path("channelRefundId").asText(), route.path("channelOrderId").asText(), route.path("channel").asText(),
                                route.path("channelTransactionId").asText(), route.path("channelRefundTransactionId").asText(),
                                "evt-concurrent-refund", "corr-concurrent-refund");
                        });
                        assertTrue(started.await(5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> callback.get(300, TimeUnit.MILLISECONDS));
                        assertEquals(paymentId, service.getIntent(paymentId).paymentIntentId());
                        commit.countDown();
                        writer.get(10, TimeUnit.SECONDS);
                        assertEquals(refundId, callback.get(10, TimeUnit.SECONDS).refundId());
                    } finally {
                        commit.countDown();
                    }
                }
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
                assertEquals(payment.path("refundedAmount"), mapper.readTree(jdbc.queryForObject(
                    "SELECT data->'refundedAmount' FROM payment_intent_snapshots WHERE id=?", String.class, paymentId)));
            } finally {
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
