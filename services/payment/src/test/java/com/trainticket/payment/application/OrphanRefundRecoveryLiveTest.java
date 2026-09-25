package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.Refund;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Exercises orphan refund recovery against PostgreSQL while the downstream stub
 * inspects the payment pool in pg_stat_activity. The deliberate control request
 * proves the observation detects an HTTP wait inside a transaction; every real
 * recovery request must instead observe zero matching idle transactions.
 */
class OrphanRefundRecoveryLiveTest {
    private static final String CASE_ID = "case-orphan-live";
    private static final String CHANNEL_REFUND_ID = "cr-orphan-live";
    private static final String REFUND_ID = "rf-orphan-live";
    private static final Money AMOUNT = Money.fromMinorUnits(1_200, "CNY");

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_ORPHAN_REFUND_RECOVERY_LIVE", matches = "true")
    void recoveryWaitsForPostSalesAndPaymentChannelWithoutAnIdleTransaction() throws Exception {
        String schema = "orphan_refund_recovery_" + UUID.randomUUID().toString().replace("-", "");
        String applicationName = schema;
        String stubUrl = required("TEST_ORPHAN_REFUND_STUB");
        AtomicReference<String> paymentId = new AtomicReference<>();
        AtomicReference<String> channel = new AtomicReference<>();
        AtomicReference<String> channelOrderId = new AtomicReference<>();
        AtomicReference<String> channelTransactionId = new AtomicReference<>();
        List<Observation> observations = new CopyOnWriteArrayList<>();
        HttpServer downstream = HttpServer.create(new InetSocketAddress(URI.create(stubUrl).getPort()), 0);
        downstream.createContext("/", exchange -> handle(
            exchange, applicationName, paymentId, channel, channelOrderId, channelTransactionId, observations));
        downstream.start();

        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(required("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                String databaseUri = required("TEST_DATABASE_URI");
                String separator = databaseUri.contains("?") ? "&" : "?";
                context = SpringApplication.run(
                    Application.class,
                    "--server.port=0",
                    "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + databaseUri + separator + "currentSchema=" + schema + "&ApplicationName=" + applicationName,
                    "--REDIS_URL=" + required("TEST_REDIS_URL"),
                    "--POST_SALES_BASE_URL=" + stubUrl
                );

                PaymentCommandService commands = context.getBean(PaymentCommandService.class);
                var intent = commands.createIntent(
                    "ord-orphan-live", "purchase", AMOUNT, "acct-orphan-live", "idem-orphan-live", "corr-orphan-live");
                var captured = commands.captureIntent(intent.paymentIntentId(), "idem-orphan-capture", "corr-orphan-capture");
                paymentId.set(captured.paymentIntentId());
                channel.set(captured.channelRef().channel());
                channelOrderId.set(captured.channelRef().channelOrderId());
                channelTransactionId.set(captured.channelRef().channelTransactionId());

                JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                transaction.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT 1 /* orphan_recovery_idle_control */", Integer.class);
                    RestClient.create(stubUrl).get().uri("/control").retrieve().toBodilessEntity();
                });
                Observation control = observations.getLast();
                assertEquals("/control", control.path());
                assertTrue(control.idleInTransaction() > 0,
                    "the pg_stat_activity control must detect an HTTP wait inside a database transaction");
                observations.clear();

                Refund recovered = context.getBean(OrphanRefundRecoveryService.class)
                    .recover(CASE_ID, CHANNEL_REFUND_ID);

                assertEquals("SETTLED", recovered.status().name());
                assertEquals("rtx-orphan-live", recovered.channelRefundTransactionId());
                assertEquals(
                    List.of(
                        new Observation("/api/v1/post-sales-cases/" + CASE_ID, 0),
                        new Observation("/api/v1/channel-refunds/" + CHANNEL_REFUND_ID, 0),
                        new Observation("/api/v1/channel-refunds/" + CHANNEL_REFUND_ID + "/submit", 0)
                    ),
                    new ArrayList<>(observations),
                    "none of the recovery's downstream waits may retain a payment database transaction"
                );
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_snapshots", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'RefundSettled'", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'PaymentRefunded'", Integer.class));
            } finally {
                if (context != null) {
                    context.close();
                }
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally {
            downstream.stop(0);
        }
    }

    private static void handle(
        HttpExchange exchange,
        String applicationName,
        AtomicReference<String> paymentId,
        AtomicReference<String> channel,
        AtomicReference<String> channelOrderId,
        AtomicReference<String> channelTransactionId,
        List<Observation> observations
    ) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/control") || path.startsWith("/api/v1/post-sales-cases/")
            || path.equals("/api/v1/channel-refunds/" + CHANNEL_REFUND_ID)
            || path.equals("/api/v1/channel-refunds/" + CHANNEL_REFUND_ID + "/submit")) {
            observations.add(new Observation(path, idleInTransactionCount(applicationName)));
        }

        String body = switch (path) {
            case "/control" -> "{}";
            case "/api/v1/channel-orders" -> "{\"channelOrderId\":\"co-orphan-live\",\"version\":1,\"requestFingerprint\":\"fp-order-live\"}";
            case "/api/v1/channel-orders/co-orphan-live/submit" -> "{\"channelOrderId\":\"co-orphan-live\",\"version\":2,\"status\":\"SUCCEEDED\",\"channelTransactionId\":\"txn-orphan-live\"}";
            case "/api/v1/post-sales-cases/" + CASE_ID -> json(Map.of(
                "caseId", CASE_ID,
                "status", "APPLIED",
                "caseType", "REFUND",
                "journeyOrderId", "ord-orphan-live",
                "decision", Map.of("refundableAmount", Map.of("currency", "CNY", "minorUnits", AMOUNT.toMinorUnits()))
            ));
            case "/api/v1/channel-refunds/" + CHANNEL_REFUND_ID -> json(Map.ofEntries(
                Map.entry("channelRefundId", CHANNEL_REFUND_ID),
                Map.entry("refundId", REFUND_ID),
                Map.entry("paymentIntentId", paymentId.get()),
                Map.entry("channelOrderId", channelOrderId.get()),
                Map.entry("originalChannelTransactionId", channelTransactionId.get()),
                Map.entry("channel", channel.get()),
                Map.entry("amount", Map.of("currency", "CNY", "minorUnits", AMOUNT.toMinorUnits())),
                Map.entry("refundReasonCode", "CUSTOMER_REQUEST"),
                Map.entry("correlationId", "corr-orphan-recovery-live"),
                Map.entry("status", "CREATED"),
                Map.entry("version", 1),
                Map.entry("requestFingerprint", "fp-refund-live")
            ));
            case "/api/v1/channel-refunds/" + CHANNEL_REFUND_ID + "/submit" -> json(Map.of(
                "channelRefundId", CHANNEL_REFUND_ID,
                "version", 2,
                "status", "SUCCEEDED",
                "channelRefundTransactionId", "rtx-orphan-live"
            ));
            default -> throw new IllegalStateException("unexpected downstream path " + path);
        };
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static int idleInTransactionCount(String applicationName) {
        try (Connection connection = DriverManager.getConnection(required("TEST_DATABASE_URL"));
             var statement = connection.prepareStatement(
                 "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND state = 'idle in transaction'")) {
            statement.setString(1, applicationName);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("pg_stat_activity could not be read", exception);
        }
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private record Observation(String path, int idleInTransaction) {
    }
}
