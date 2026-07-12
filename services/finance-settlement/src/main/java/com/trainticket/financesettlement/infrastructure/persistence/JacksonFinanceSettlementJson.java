package com.trainticket.financesettlement.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.ReconciliationStatus;
import com.trainticket.financesettlement.domain.RevenueAllocation;
import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SettlementPeriod;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

final class JacksonFinanceSettlementJson {
    private JacksonFinanceSettlementJson() {}

    record RevenueRecognitionSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static RevenueRecognitionSnapshot of(ObjectNode data) { return new RevenueRecognitionSnapshot(data); } }
    record ReconciliationCaseSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static ReconciliationCaseSnapshot of(ObjectNode data) { return new ReconciliationCaseSnapshot(data); } }
    record ReconciliationBatchSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static ReconciliationBatchSnapshot of(ObjectNode data) { return new ReconciliationBatchSnapshot(data); } }
    record SupplierSettlementSnapshot(@JsonValue ObjectNode data) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) static SupplierSettlementSnapshot of(ObjectNode data) { return new SupplierSettlementSnapshot(data); } }
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

    static ReconciliationBatchSnapshot batchSnapshot(ReconciliationBatch batch, ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("batchId", batch.batchId());
        n.put("settlementDate", batch.settlementDate().toString());
        n.put("cutoffAt", batch.cutoffAt().toString());
        n.put("currency", batch.currency().getCurrencyCode());
        ArrayNode entries = n.putArray("entries");
        for (ReconciliationEntry entry : batch.entries()) {
            ObjectNode e = entries.addObject();
            e.put("entryId", entry.entryId());
            e.put("orderId", entry.orderId());
            e.put("paymentIntentId", entry.paymentIntentId());
            money(e.putObject("platformAmount"), entry.platformAmount());
            money(e.putObject("channelAmount"), entry.channelAmount());
            e.put("status", entry.status().name());
            money(e.putObject("variance"), entry.variance());
            ArrayNode sourceEventIds = e.putArray("sourceEventIds");
            entry.sourceEventIds().forEach(sourceEventIds::add);
        }
        return new ReconciliationBatchSnapshot(n);
    }

    static ReconciliationBatch toBatch(ReconciliationBatchSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        List<ReconciliationEntry> entries = new ArrayList<>();
        n.withArray("entries").forEach(item -> {
            ObjectNode e = (ObjectNode) item;
            List<String> sourceEventIds = new ArrayList<>();
            e.withArray("sourceEventIds").forEach(id -> sourceEventIds.add(id.asText()));
            entries.add(new ReconciliationEntry(e.path("entryId").asText(), e.path("orderId").asText(), e.path("paymentIntentId").asText(),
                money(e.withObject("platformAmount")), money(e.withObject("channelAmount")), ReconciliationStatus.valueOf(e.path("status").asText()),
                money(e.withObject("variance")), sourceEventIds));
        });
        return ReconciliationBatch.rehydrate(n.path("batchId").asText(), LocalDate.parse(n.path("settlementDate").asText()),
            Instant.parse(n.path("cutoffAt").asText()), Currency.getInstance(n.path("currency").asText()), entries);
    }

    static SupplierSettlementSnapshot supplierSnapshot(SupplierSettlement settlement, ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("supplierSettlementId", settlement.supplierSettlementId());
        n.put("supplierId", settlement.supplierId());
        n.put("startDate", settlement.period().startDate().toString());
        n.put("endDate", settlement.period().endDate().toString());
        n.put("frequency", settlement.period().frequency().name());
        n.put("platformCommissionPct", settlement.platformCommissionPct().toPlainString());
        n.put("withholdingTaxPct", settlement.withholdingTaxPct().toPlainString());
        n.put("currency", settlement.grossRevenue().currency().getCurrencyCode());
        ArrayNode allocations = n.putArray("allocations");
        for (RevenueAllocation allocation : settlement.allocations()) {
            ObjectNode a = allocations.addObject();
            a.put("orderId", allocation.orderId());
            money(a.putObject("grossRevenue"), allocation.grossRevenue());
            money(a.putObject("platformCommission"), allocation.platformCommission());
            money(a.putObject("taxesWithheld"), allocation.taxesWithheld());
            money(a.putObject("supplierPayable"), allocation.supplierPayable());
            money(a.putObject("adjustments"), allocation.adjustments());
        }
        return new SupplierSettlementSnapshot(n);
    }

    static SupplierSettlement toSupplier(SupplierSettlementSnapshot snapshot, ObjectMapper mapper) {
        ObjectNode n = snapshot.data();
        List<RevenueAllocation> allocations = new ArrayList<>();
        n.withArray("allocations").forEach(item -> {
            ObjectNode a = (ObjectNode) item;
            allocations.add(new RevenueAllocation(a.path("orderId").asText(), money(a.withObject("grossRevenue")), money(a.withObject("platformCommission")),
                money(a.withObject("taxesWithheld")), money(a.withObject("supplierPayable")), money(a.withObject("adjustments"))));
        });
        return SupplierSettlement.rehydrate(n.path("supplierSettlementId").asText(), n.path("supplierId").asText(),
            new SettlementPeriod(LocalDate.parse(n.path("startDate").asText()), LocalDate.parse(n.path("endDate").asText()), SettlementFrequency.valueOf(n.path("frequency").asText())),
            new BigDecimal(n.path("platformCommissionPct").asText()), new BigDecimal(n.path("withholdingTaxPct").asText()), allocations, Currency.getInstance(n.path("currency").asText()));
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
