package com.trainticket.payment.adapters.paymentchannel;

import com.trainticket.payment.application.PaymentChannelClient;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class HttpPaymentChannelClient implements PaymentChannelClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HttpPaymentChannelClient(ObjectMapper mapper) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build(), mapper, System.getenv().getOrDefault("PAYMENT_CHANNEL_URL", "http://payment-channel:8080"));
    }

    HttpPaymentChannelClient(HttpClient client, ObjectMapper mapper, String baseUrl) {
        this.client = Objects.requireNonNull(client, "client is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl is required").replaceAll("/+$", "");
    }

    @Override
    public HandoffOrder handoffCapture(PaymentIntent intent, String orderIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef requestedRef) {
        ChannelRef ref = Objects.requireNonNull(requestedRef, "channelRef is required");
        JsonNode created = post("/api/v1/channel-orders", orderIdempotencyKey, correlationId, Map.of(
            "paymentIntentId", intent.paymentIntentId(),
            "businessRef", intent.businessRef(),
            "purpose", intent.purpose(),
            "channel", ref.channel(),
            "amount", money(intent.amount()),
            "sourceCommandId", commandId(orderIdempotencyKey),
            "correlationId", correlationId
        ));
        JsonNode submitted = post("/api/v1/channel-orders/" + text(created, "channelOrderId") + "/submit", submitIdempotencyKey, correlationId, Map.of(
            "expectedVersion", created.path("version").asLong(),
            "requestFingerprint", text(created, "requestFingerprint")
        ));
        ChannelRef out = new ChannelRef(ref.channel(), text(submitted, "channelOrderId"), null, optionalText(submitted, "channelTransactionId"), null, null, ref.faultSeedRef());
        return new HandoffOrder(out.channelOrderId(), text(submitted, "status"), out.channelTransactionId(), out);
    }

    @Override
    public HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String refundIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef originalRoute) {
        ChannelRef ref = Objects.requireNonNull(originalRoute, "channelRef is required");
        JsonNode created = post("/api/v1/channel-refunds", refundIdempotencyKey, correlationId, Map.of(
            "refundId", refund.refundId(),
            "paymentIntentId", intent.paymentIntentId(),
            "channelOrderId", ref.channelOrderId(),
            "originalChannelTransactionId", ref.channelTransactionId(),
            "channel", ref.channel(),
            "amount", money(refund.amount()),
            "refundReasonCode", refund.reasonCode(),
            "sourceCommandId", commandId(refundIdempotencyKey),
            "correlationId", correlationId
        ));
        JsonNode submitted = post("/api/v1/channel-refunds/" + text(created, "channelRefundId") + "/submit", submitIdempotencyKey, correlationId, Map.of(
            "expectedVersion", created.path("version").asLong(),
            "requestFingerprint", text(created, "requestFingerprint")
        ));
        ChannelRef out = new ChannelRef(ref.channel(), ref.channelOrderId(), text(submitted, "channelRefundId"), ref.channelTransactionId(), optionalText(submitted, "channelRefundTransactionId"), null, ref.faultSeedRef());
        return new HandoffRefund(out.channelRefundId(), text(submitted, "status"), out.channelRefundTransactionId(), out);
    }

    private JsonNode post(String path, String idempotencyKey, String correlationId, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", stripCommandPrefix(idempotencyKey))
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("payment-channel returned HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("payment-channel request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("payment-channel request failed", exception);
        }
    }

    private static Map<String, Object> money(Money money) {
        return Map.of("currency", money.currency().getCurrencyCode(), "minorUnits", money.toMinorUnits());
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("payment-channel response missing " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String commandId(String idempotencyKey) {
        return "cmd-" + stripCommandPrefix(idempotencyKey);
    }

    private static String stripCommandPrefix(String value) {
        String trimmed = Objects.requireNonNull(value, "idempotencyKey is required").trim();
        return trimmed.startsWith("cmd-") ? trimmed.substring(4) : trimmed;
    }
}
