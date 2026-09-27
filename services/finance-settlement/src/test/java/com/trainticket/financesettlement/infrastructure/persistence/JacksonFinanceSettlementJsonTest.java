package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.ReconciliationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class JacksonFinanceSettlementJsonTest {
    @Test
    void aBatchKeepsItsTotalsAndOnlyTheEntriesThatNeedAttention() throws Exception {
        Currency currency = Currency.getInstance("CNY");
        ReconciliationEntry matched = ReconciliationEntry.compare(
            "re-ord-1", "ord-1", "pi-1", Money.of("CNY", "10.00"), Money.of("CNY", "10.00"), true, true, List.of("evt-1", "line-1"));
        ReconciliationEntry mismatch = ReconciliationEntry.compare(
            "re-ord-2", "ord-2", "pi-2", Money.of("CNY", "20.00"), Money.of("CNY", "19.00"), true, true, List.of("evt-2", "line-2"));
        ReconciliationBatch batch = ReconciliationBatch.rehydrate(
            "rb-1", LocalDate.parse("2026-07-02"), Instant.parse("2026-07-03T00:00:00Z"), currency, List.of(matched, mismatch))
            .withVersion(1);
        ObjectMapper mapper = new ObjectMapper();

        JacksonFinanceSettlementJson.ReconciliationBatchSnapshot snapshot = JacksonFinanceSettlementJson.batchSnapshot(batch, mapper);
        assertEquals(1, snapshot.data().withArray("entries").size(), "only the entry that needs attention is kept");

        ReconciliationBatch rehydrated = JacksonFinanceSettlementJson.toBatch(snapshot, mapper);
        assertEquals(2, rehydrated.report().totalEntries(), "the day's totals survive");
        assertEquals(1, rehydrated.report().matchedEntries());
        assertEquals(new java.math.BigDecimal("0.5000"), rehydrated.report().matchRate());
        assertEquals(1, rehydrated.entries().size());
        assertEquals(ReconciliationStatus.AMOUNT_MISMATCH, rehydrated.entries().getFirst().status());
        assertEquals(Money.of("CNY", "1.00"), rehydrated.report().totalVariance());
    }

    @Test
    void snapshotSerializesBusinessFieldsAtTopLevel() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode data = objectMapper.createObjectNode();
        data.put("businessField", "value");

        String json = objectMapper.writeValueAsString(new JacksonFinanceSettlementJson.RevenueRecognitionSnapshot(data));
        JsonNode root = objectMapper.readTree(json);

        assertEquals("value", root.path("businessField").asText());
        assertTrue(root.has("businessField"));
        assertFalse(root.has("data"));
    }
}
