package com.trainticket.financesettlement.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.financesettlement.application.PlatformRefundSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the refunds payment settled on a date, for the settlement reconciliation.
 *
 * <p>Paged by payment's own index order, so a day of refunds is a handful of
 * range scans rather than one query per channel line.
 */
@Component
public class PlatformRefundFactsClient implements PlatformRefundSource {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;
    private final String template;
    // Parsed by hand, like the other reference clients in this adapter: the
    // builder's default converters are Jackson 3's and this service reads
    // Jackson 2's JsonNode.
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public PlatformRefundFactsClient(@Qualifier("financeRestClientBuilder") RestClient.Builder builder,
                                     @Value("${FINANCE_REFERENCE_URL_TEMPLATE:http://%s:8080}") String template) {
        this.template = template;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public Page page(LocalDate settlementDate, String cursor, int limit) {
        String from = settlementDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString();
        String to = settlementDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString();
        StringBuilder query = new StringBuilder("/api/v1/internal/refund-settlements?settledFrom=")
            .append(encode(from)).append("&settledTo=").append(encode(to))
            .append("&limit=").append(Math.min(Math.max(limit, 1), MAX_PAGE_SIZE));
        if (cursor != null && !cursor.isBlank()) {
            query.append("&after=").append(encode(cursor));
        }
        JsonNode body = client.get()
            .uri(template.replace("%s", "payment") + query)
            .exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("refund settlements returned " + response.getStatusCode());
                }
                return mapper.readTree(response.getBody());
            });
        if (body == null || body.isNull()) return new Page(List.of(), null);
        List<Refund> refunds = new ArrayList<>();
        for (JsonNode node : body.path("items")) {
            refunds.add(new Refund(
                node.path("refundId").asText(""),
                node.path("paymentIntentId").asText(""),
                node.path("businessReference").asText(""),
                node.path("sourceCaseRef").asText(""),
                node.path("currency").asText("CNY"),
                node.path("amountMinorUnits").asLong(0)
            ));
        }
        String next = body.hasNonNull("nextCursor") ? body.path("nextCursor").asText() : null;
        return new Page(refunds, next);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
