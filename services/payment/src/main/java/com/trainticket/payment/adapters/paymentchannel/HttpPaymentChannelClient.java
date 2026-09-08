package com.trainticket.payment.adapters.paymentchannel;

import com.trainticket.payment.application.PaymentChannelClient;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public final class HttpPaymentChannelClient implements PaymentChannelClient {
    private final RestClient restClient;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired

    public HttpPaymentChannelClient(ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        this(mapper, restClientBuilder, System.getenv().getOrDefault("PAYMENT_CHANNEL_URL", "http://payment-channel:8080"));
    }

    HttpPaymentChannelClient(ObjectMapper mapper, RestClient.Builder restClientBuilder, String baseUrl) {
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        Objects.requireNonNull(restClientBuilder, "restClientBuilder is required");
        // Plain HTTP/1.1 factory, matching the version this client previously
        // pinned on java.net.http.HttpClient.
        //
        // The builder must come from the container: java-kit installs the
        // trace-propagation interceptor on RestClient.Builder beans only, so a
        // hand-built client would silently drop the outbound W3C traceparent and
        // payment-channel would open a new trace.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        String normalizedBaseUrl = Objects.requireNonNull(baseUrl, "baseUrl is required").replaceAll("/+$", "");
        this.restClient = restClientBuilder.requestFactory(requestFactory).baseUrl(normalizedBaseUrl).build();
    }

    @Override
    public HandoffOrder handoffCapture(PaymentIntent intent, String orderIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef requestedRef) {
        ChannelRef ref = Objects.requireNonNull(requestedRef, "channelRef is required");
        JsonNode created = post("/api/v1/channel-orders", orderIdempotencyKey, correlationId, Map.of(
            "paymentIntentId", intent.paymentIntentId(),
            "businessRef", intent.businessRef(),
            "purpose", intent.purpose(),
            "channel", ChannelRouter.channelFacingId(ref.channel()),
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
            "channel", ChannelRouter.channelFacingId(ref.channel()),
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
            // The interceptor java-kit installed on the injected builder adds the
            // outbound traceparent; the correlation and idempotency headers are
            // set here exactly as before.
            String responseBody = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", stripCommandPrefix(idempotencyKey))
                .header("X-Correlation-Id", correlationId)
                .body(body)
                .exchange((request, response) -> {
                    int statusCode = response.getStatusCode().value();
                    if (statusCode >= 400) {
                        // Include the path and the response body, not just the
                        // status. payment-channel answers a rejection with
                        // {"code":"VALIDATION_FAILED","details":{...}} naming the
                        // offending field, and discarding it left "payment-channel
                        // returned HTTP 400" as the only evidence of a saga-killing
                        // failure -- unactionable, and indistinguishable between a
                        // malformed request here and a rule rejection there.
                        throw new IllegalStateException(
                            "payment-channel POST " + path + " returned HTTP " + statusCode
                                + ": " + readBodySafely(response));
                    }
                    return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }, false);
            return mapper.readTree(responseBody);
        } catch (Exception exception) {
            throw new IllegalStateException(
                "payment-channel request failed: POST " + path, exception);
        }
    }

    /**
     * Best-effort read of an error response body for the exception message.
     * Truncated because this ends up in a log line, and never allowed to throw:
     * losing the status code because the body could not be read would be worse
     * than losing the body.
     */
    private static String readBodySafely(org.springframework.http.client.ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return "<empty body>";
            }
            return body.length() > 512 ? body.substring(0, 512) + "...<truncated>" : body;
        } catch (Exception ignored) {
            return "<body unreadable>";
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
