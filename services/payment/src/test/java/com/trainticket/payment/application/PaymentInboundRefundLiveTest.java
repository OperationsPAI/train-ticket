package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.payment.Application;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the inbound refund handoff against PostgreSQL and a stub payment channel, and reads
 * pg_stat_activity while the channel request is in flight. A channel call made inside a
 * transaction leaves the caller's session idle in transaction for the whole HTTP call.
 */
class PaymentInboundRefundLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PAYMENT_CHANNEL_STUB", matches = ".+")
    void inboundRefundHandoffHoldsNoDatabaseTransaction() throws Exception {
        String schema = "payment_inbound_refund_" + UUID.randomUUID().toString().replace("-", "");
        List<Integer> idleInTransaction = new CopyOnWriteArrayList<>();
        HttpServer channel = HttpServer.create(new InetSocketAddress(URI.create(System.getenv("TEST_PAYMENT_CHANNEL_STUB")).getPort()), 0);
        channel.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            if (path.equals("/api/v1/channel-refunds/cr-live/submit")) {
                idleInTransaction.add(idleInTransactionCount());
            }
            byte[] body = channelResponse(path).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        channel.start();
        ConfigurableApplicationContext context = null;
        try (var admin = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try {
                context = SpringApplication.run(Application.class, "--server.port=0", "--payment.messaging.enabled=false",
                    "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URI") + "?currentSchema=" + schema,
                    "--REDIS_URL=" + System.getenv("TEST_REDIS_URL"));
                var commands = context.getBean(PaymentCommandService.class);
                var handler = context.getBean(PaymentInboundEventHandler.class);
                PaymentIntent intent = commands.createIntent("ord-live-refund", "purchase", Money.fromMinorUnits(1_200, "CNY"), "acct-1", "idem-live", "corr-live");
                commands.captureIntent(intent.paymentIntentId(), "idem-capture-live", "corr-capture-live");
                assertEquals("CAPTURED", commands.getIntent(intent.paymentIntentId()).status().name());
                idleInTransaction.clear();
                String eventId = PrefixedIds.newEventId();

                assertEquals(HandlerResult.SUCCESS, handler.handle(postSalesApproved(eventId, intent.paymentIntentId())));

                assertEquals(List.of(0), idleInTransaction, "the channel call must not hold a database transaction open");
                Refund refund = commands.refundsForCase("case-live").getFirst();
                assertEquals("SUBMITTED", refund.status().name());
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'RefundRequested'", Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_snapshots", Integer.class));
            } finally {
                if (context != null) context.close();
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally {
            channel.stop(0);
        }
    }

    private static int idleInTransactionCount() {
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'")) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("pg_stat_activity could not be read", exception);
        }
    }

    private static String channelResponse(String path) {
        return switch (path) {
            case "/api/v1/channel-orders" -> "{\"channelOrderId\":\"co-live\",\"version\":1,\"requestFingerprint\":\"fp-live\"}";
            case "/api/v1/channel-orders/co-live/submit" -> "{\"channelOrderId\":\"co-live\",\"version\":2,\"status\":\"SUCCEEDED\",\"channelTransactionId\":\"ctx-live\"}";
            case "/api/v1/channel-refunds" -> "{\"channelRefundId\":\"cr-live\",\"version\":1,\"requestFingerprint\":\"fp-live\"}";
            case "/api/v1/channel-refunds/cr-live/submit" -> "{\"channelRefundId\":\"cr-live\",\"version\":2,\"status\":\"SUCCEEDED\",\"channelRefundTransactionId\":\"crt-live\"}";
            default -> throw new IllegalStateException("unexpected channel path " + path);
        };
    }

    private static EventEnvelope postSalesApproved(String eventId, String paymentIntentId) {
        return new EventEnvelope(
            eventId,
            "PostSalesApproved",
            Instant.parse("2026-07-05T10:31:00Z"),
            PrefixedIds.newCorrelationId(),
            PrefixedIds.newEventId(),
            "post-sales",
            1,
            Map.of(
                "caseId", "case-live",
                "orderId", "ord-live-refund",
                "approvedActions", Map.of(
                    "paymentIntentId", paymentIntentId,
                    "refundAmount", Map.of("currency", "CNY", "minorUnits", 1_200L)
                )
            )
        );
    }
}
