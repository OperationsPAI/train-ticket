package com.trainticket.postsales.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "post-sales.messaging.redis.enabled=false")
class PostSalesControllerTest {
    private static final String OPEN_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073001";
    private static final String EVALUATE_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073002";
    private static final String APPROVE_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073003";
    private static final String VALIDATION_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073004";
    private static final String REPLAY_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073005";
    private static final String REUSED_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073006";
    private static final String GENERATED_CORRELATION_KEY = "01890f47-9b7c-7cc2-98c4-dc0c0c073007";
    private static final String UUID4_KEY = "550e8400-e29b-41d4-a716-446655440000";

    private MockMvc mockMvc;

    @Autowired
    void setApplicationContext(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(com.trainticket.postsales.RequestContextFilter.class),
                new com.trainticket.platformkit.idempotency.IdempotencyFilter(
                    context.getBean(com.trainticket.platformkit.idempotency.IdempotencyStore.class),
                    context.getBean(com.trainticket.platformkit.http.CanonicalErrorWriter.class)
                )
            )
            .build();
    }

    @Test
    void openEvaluateApproveAndGetHappyPath() throws Exception {
        String caseId = openCase(OPEN_KEY)
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-test-1"))
            .andExpect(jsonPath("$.caseId", notNullValue()))
            .andExpect(jsonPath("$.journeyOrderId", equalTo("ord-test-1")))
            .andExpect(jsonPath("$.status", equalTo("OPENED")))
            .andReturn().getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", caseId)
                .header("Idempotency-Key", EVALUATE_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.eligible", equalTo(true)))
            .andExpect(jsonPath("$.refundableAmount.currency", equalTo("CNY")))
            .andExpect(jsonPath("$.refundableAmount.minorUnits", equalTo(0)));

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", caseId)
                .header("Idempotency-Key", APPROVE_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.status", equalTo("APPROVED")));

        mockMvc.perform(get("/api/v1/post-sales-cases/{caseId}", caseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.status", equalTo("APPROVED")));
    }

    @Test
    void validationFailureUsesCanonicalErrorBody() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", VALIDATION_KEY)
                .header("X-Correlation-Id", "corr-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.correlationId", equalTo("corr-validation")))
            .andExpect(jsonPath("$.details", notNullValue()));
    }

    @Test
    void idempotentReplayReturnsOriginalOpenResult() throws Exception {
        MvcResult first = openCase(REPLAY_KEY).andExpect(status().isCreated()).andReturn();
        openCase(REPLAY_KEY)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.caseId", equalTo(first.getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0])));
    }

    @Test
    void idempotencyKeyReuseWithDifferentBodyReturns422() throws Exception {
        openCase(REUSED_KEY).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", REUSED_KEY)
                .header("X-Correlation-Id", "corr-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "journeyOrderId": "ord-test-different",
                      "caseType": "REFUND",
                      "scope": {
                        "orderItemRefs": ["oi-test-1"],
                        "segmentRefs": ["seg-test-1"],
                        "travelerRefs": ["tvl-test-1"],
                        "entitlementRefs": ["ent-test-1"]
                      },
                      "reasonCode": "CUSTOMER_REQUEST",
                      "actorRef": "acct-test-1"
                    }
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("IDEMPOTENCY_KEY_REUSED")))
            .andExpect(jsonPath("$.correlationId", equalTo("corr-test-1")));
    }

    @Test
    void missingCorrelationHeaderUsesGeneratedRequestCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases")
                .header("Idempotency-Key", GENERATED_CORRELATION_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "journeyOrderId": "ord-generated-correlation",
                      "caseType": "REFUND",
                      "scope": {
                        "orderItemRefs": ["oi-test-1"],
                        "segmentRefs": ["seg-test-1"],
                        "travelerRefs": ["tvl-test-1"],
                        "entitlementRefs": ["ent-test-1"]
                      },
                      "reasonCode": "CUSTOMER_REQUEST",
                      "actorRef": "acct-test-1"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(header().string("X-Correlation-Id", not(equalTo(GENERATED_CORRELATION_KEY))));
    }

    @Test
    void openRejectsMalformedIdempotencyKey() throws Exception {
        openCase("not-a-uuid")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void openRejectsUuid4IdempotencyKey() throws Exception {
        openCase(UUID4_KEY)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void evaluateRejectsMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", "psc-test-case")
                .header("Idempotency-Key", "not-a-uuid")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void evaluateRejectsUuid4IdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", "psc-test-case")
                .header("Idempotency-Key", UUID4_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void approveRejectsMalformedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", "psc-test-case")
                .header("Idempotency-Key", "not-a-uuid")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    @Test
    void approveRejectsUuid4IdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", "psc-test-case")
                .header("Idempotency-Key", UUID4_KEY)
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")))
            .andExpect(jsonPath("$.message", equalTo("Idempotency-Key header must be a UUID v7")));
    }

    private org.springframework.test.web.servlet.ResultActions openCase(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/post-sales-cases")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Correlation-Id", "corr-test-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "journeyOrderId": "ord-test-1",
                  "caseType": "REFUND",
                  "scope": {
                    "orderItemRefs": ["oi-test-1"],
                    "segmentRefs": ["seg-test-1"],
                    "travelerRefs": ["tvl-test-1"],
                    "entitlementRefs": ["ent-test-1"]
                  },
                  "reasonCode": "CUSTOMER_REQUEST",
                  "actorRef": "acct-test-1"
                }
                """));
    }
}
