package com.trainticket.financesettlement.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

final class JacksonFinanceSettlementJson {
    private JacksonFinanceSettlementJson() {}

    record RevenueRecognitionSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static RevenueRecognitionSnapshot of(ObjectNode data) { return new RevenueRecognitionSnapshot(data); } }
    record ReconciliationCaseSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static ReconciliationCaseSnapshot of(ObjectNode data) { return new ReconciliationCaseSnapshot(data); } }
    record InvoiceSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static InvoiceSnapshot of(ObjectNode data) { return new InvoiceSnapshot(data); } }

    static RevenueRecognitionSnapshot revenueSnapshot(RevenueRecognition recognition, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("revenueRecognitionId", recognition.revenueRecognitionId());
        node.put("orderId", recognition.orderId());
        node.put("orderItemId", recognition.orderItemId());
        node.put("componentCode", recognition.componentCode());
        money(node.putObject("amount"), recognition.amount());
        node.put("recognitionPolicyVersion", recognition.recognitionPolicyVersion());
        node.put("sourceEventId", recognition.sourceEventId());
        node.put("recognizedAt", recognition.recognizedAt().toString());
        node.put("reversed", recognition.reversed());
        money(node.putObject("reversedAmount"), recognition.reversedAmount());
        if (recognition.reversalReason() != null) node.put("reversalReason", recognition.reversalReason());
        return new RevenueRecognitionSnapshot(node);
    }

    static RevenueRecognition toRevenue(RevenueRecognitionSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        return RevenueRecognition.rehydrate(n.path("revenueRecognitionId").asText(), n.path("orderId").asText(), n.path("orderItemId").asText(), n.path("componentCode").asText(),
            money(n.withObject("amount")), n.path("recognitionPolicyVersion").asText(), n.path("sourceEventId").asText(), Instant.parse(n.path("recognizedAt").asText()),
            n.path("reversed").asBoolean(false), money(n.withObject("reversedAmount")), textOrNull(n, "reversalReason"));
    }

    static ReconciliationCaseSnapshot caseSnapshot(ReconciliationCase c, ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("reconciliationCaseId", c.reconciliationCaseId());
        n.put("orderId", c.orderId());
        n.put("paymentIntentId", c.paymentIntentId());
        n.put("differenceType", c.differenceType());
        money(n.putObject("expectedAmount"), c.expectedAmount());
        money(n.putObject("actualAmount"), c.actualAmount());
        n.put("description", c.description());
        n.put("openedAt", c.openedAt().toString());
        n.put("status", c.status().name());
        if (c.resolution() != null) n.put("resolution", c.resolution());
        if (c.resolutionNote() != null) n.put("resolutionNote", c.resolutionNote());
        return new ReconciliationCaseSnapshot(n);
    }

    static ReconciliationCase toCase(ReconciliationCaseSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        return ReconciliationCase.rehydrate(n.path("reconciliationCaseId").asText(), n.path("orderId").asText(), n.path("paymentIntentId").asText(), n.path("differenceType").asText(),
            money(n.withObject("expectedAmount")), money(n.withObject("actualAmount")), n.path("description").asText(), Instant.parse(n.path("openedAt").asText()),
            ReconciliationCase.ReconciliationCaseStatus.valueOf(n.path("status").asText()), textOrNull(n, "resolution"), textOrNull(n, "resolutionNote"));
    }

    static InvoiceSnapshot invoiceSnapshot(Invoice invoice, ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("invoiceId", invoice.invoiceId());
        n.put("orderId", invoice.orderId());
        n.put("invoiceNumber", invoice.invoiceNumber());
        money(n.putObject("totalAmount"), invoice.totalAmount());
        ArrayNode ids = n.putArray("revenueRecognitionIds");
        invoice.revenueRecognitionIds().forEach(ids::add);
        n.put("generatedAt", invoice.generatedAt().toString());
        return new InvoiceSnapshot(n);
    }

    static Invoice toInvoice(InvoiceSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        List<String> ids = new ArrayList<>();
        n.withArray("revenueRecognitionIds").forEach(id -> ids.add(id.asText()));
        return Invoice.rehydrate(n.path("invoiceId").asText(), n.path("orderId").asText(), n.path("invoiceNumber").asText(), money(n.withObject("totalAmount")), ids, Instant.parse(n.path("generatedAt").asText()));
    }

    private static void money(ObjectNode n, Money money) { n.put("currency", money.currency().getCurrencyCode()); n.put("amount", money.amount().toPlainString()); }
    private static Money money(ObjectNode n) { return new Money(Currency.getInstance(n.path("currency").asText()), new BigDecimal(n.path("amount").asText())); }
    private static String textOrNull(ObjectNode node, String field) { return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null; }
}
