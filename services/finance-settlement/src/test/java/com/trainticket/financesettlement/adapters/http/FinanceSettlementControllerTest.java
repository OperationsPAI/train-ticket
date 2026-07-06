package com.trainticket.financesettlement.adapters.http;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainticket.financesettlement.application.FinanceSettlementApplicationService;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.time.Instant;
import com.trainticket.platformkit.idempotency.UuidV7;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "finance.messaging.redis.enabled=false")
@AutoConfigureMockMvc
class FinanceSettlementControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    FinanceSettlementApplicationService service;

    private RevenueRecognition recognition;
    private ReconciliationCase reconciliationCase;
    private String orderId;

    @BeforeEach
    void setUp() {
        orderId = "ord-" + UUID.randomUUID();
        recognition = RevenueRecognition.recognize(
            orderId, "item-1", "fare", Money.of("CNY", "120.00"), "policy-v1",
            "evt-source", Instant.parse("2026-07-05T10:00:00Z"), Instant.parse("2026-07-05T10:00:01Z"),
            "cmd-seed", "corr-seed");
        service.saveAndPublish(recognition);

        reconciliationCase = ReconciliationCase.open(
            orderId, "pi-1", "amount-mismatch", Money.of("CNY", "120.00"), Money.of("CNY", "119.00"),
            "channel amount differs", Instant.parse("2026-07-05T10:01:00Z"), "cmd-seed", "corr-seed");
        service.saveAndPublish(reconciliationCase);
    }

    @Test
    void getsRevenueRecognition() throws Exception {
        mockMvc.perform(get("/api/v1/revenue-recognitions/{id}", recognition.revenueRecognitionId())
                .header("X-Correlation-Id", "corr-http"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-http"))
            .andExpect(jsonPath("$.revenueRecognitionId").value(recognition.revenueRecognitionId()))
            .andExpect(jsonPath("$.orderItemId").value("item-1"))
            .andExpect(jsonPath("$.amount.currency").value("CNY"))
            .andExpect(jsonPath("$.amount.minorUnits").value(12000))
            .andExpect(jsonPath("$.recognizedAt").value("2026-07-05T10:00:00Z"));
    }

    @Test
    void getsReconciliationCase() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliation-cases/{id}", reconciliationCase.reconciliationCaseId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reconciliationCaseId").value(reconciliationCase.reconciliationCaseId()))
            .andExpect(jsonPath("$.expectedAmount.minorUnits").value(12000))
            .andExpect(jsonPath("$.actualAmount.minorUnits").value(11900))
            .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void listsReconciliationCases() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliation-cases").param("orderId", orderId).param("limit", "20").param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.limit").value(20))
            .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void validationFailureReturnsCanonicalErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliation-cases").param("limit", "0").header("X-Correlation-Id", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0f001"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.correlationId").value("corr-0194f2e0-7b3e-7610-8284-5c26e8b0f001"));
    }

    @Test
    void notFoundReturnsCanonicalErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/revenue-recognitions/missing").header("X-Correlation-Id", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0f002"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.correlationId").value("corr-0194f2e0-7b3e-7610-8284-5c26e8b0f002"));
    }

    @Test
    void generateInvoiceIsIdempotent() throws Exception {
        String key = UuidV7.generate();
        mockMvc.perform(post("/api/v1/invoices")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"orderId\":\"" + orderId + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(orderId))
            .andExpect(jsonPath("$.totalAmount.minorUnits").value(12000));

        mockMvc.perform(post("/api/v1/invoices")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"orderId\":\"" + orderId + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(orderId))
            .andExpect(jsonPath("$.totalAmount.minorUnits").value(12000));
    }

    @Test
    void generateInvoiceRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/invoices")
                .contentType("application/json")
                .content("{\"orderId\":\"" + orderId + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void generateInvoiceRejectsNonUuidV7IdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/invoices")
                .header("Idempotency-Key", "550e8400-e29b-41d4-a716-446655440000")
                .contentType("application/json")
                .content("{\"orderId\":\"" + orderId + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
