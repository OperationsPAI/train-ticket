package com.trainticket.financesettlement.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.financesettlement.application.ChannelStatementLineSource;
import com.trainticket.financesettlement.domain.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the channel's own statement lines from payment-channel.
 *
 * The statement is already the channel's side of the money and payment-channel
 * already stores it normalised, so the reconciliation reads it rather than
 * asking for a fan-out of one event per line: the measured channel-day is
 * 342,648 lines, which is neither an event stream anyone should publish nor a
 * request anyone should make in one piece.
 */
@Component
public class ChannelStatementFactsClient implements ChannelStatementLineSource {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // A page is 500 lines of one statement; the first read of a statement is a
    // row lookup, not a scan, so this is generous for what the endpoint does.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;
    private final String template;
    // Parsed by hand, like the other reference client in this adapter: the
    // builder's default message converters are Jackson 3's and this service
    // reads Jackson 2's JsonNode, so asking the converter for one fails.
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public ChannelStatementFactsClient(@Qualifier("financeRestClientBuilder") RestClient.Builder builder,
                                       @Value("${FINANCE_REFERENCE_URL_TEMPLATE:http://%s:8080}") String template) {
        this.template = template;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public Page page(String channelStatementId, long after, int limit) {
        JsonNode body = client.get()
            .uri(template.replace("%s", "payment-channel") + "/api/v1/channel-statements/{id}/lines?after={after}&limit={limit}",
                channelStatementId, Math.max(0, after), Math.min(limit, MAX_PAGE_SIZE))
            .exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("channel statement lines returned " + response.getStatusCode());
                }
                return mapper.readTree(response.getBody());
            });
        if (body == null || body.isNull()) return new Page(List.of(), null, 0);
        List<Line> lines = new ArrayList<>();
        for (JsonNode node : body.path("items")) {
            lines.add(new Line(
                node.path("statementLineId").asText(""),
                node.path("lineType").asText(""),
                node.path("channelOrderId").asText(""),
                node.path("channelRefundId").asText(""),
                node.path("paymentIntentId").asText(""),
                node.path("refundId").asText(""),
                money(node.path("actualAmount"))
            ));
        }
        Long nextCursor = body.hasNonNull("nextCursor") ? body.path("nextCursor").asLong() : null;
        return new Page(lines, nextCursor, body.path("lineCount").asInt(lines.size()));
    }

    private static Money money(JsonNode amount) {
        String currencyCode = amount.path("currency").asText("CNY");
        long minorUnits = amount.path("minorUnits").asLong(0);
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return Money.of(currencyCode, BigDecimal.valueOf(minorUnits).movePointLeft(fractionDigits).toPlainString());
    }
}
