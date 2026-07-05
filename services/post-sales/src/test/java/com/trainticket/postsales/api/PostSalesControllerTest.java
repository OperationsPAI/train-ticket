package com.trainticket.postsales.api;

import static org.hamcrest.Matchers.equalTo;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "post-sales.messaging.redis.enabled=false")
class PostSalesControllerTest {
    private MockMvc mockMvc;

    @Autowired
    void setApplicationContext(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(com.trainticket.postsales.RequestContextFilter.class)).build();
    }

    @Test
    void openEvaluateApproveAndGetHappyPath() throws Exception {
        String caseId = openCase("cmd-test-open-1")
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-test-1"))
            .andExpect(jsonPath("$.caseId", notNullValue()))
            .andExpect(jsonPath("$.journeyOrderId", equalTo("ord-test-1")))
            .andExpect(jsonPath("$.status", equalTo("OPENED")))
            .andReturn().getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/evaluate", caseId)
                .header("Idempotency-Key", "cmd-test-evaluate-1")
                .header("X-Correlation-Id", "corr-test-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId", equalTo(caseId)))
            .andExpect(jsonPath("$.eligible", equalTo(true)))
            .andExpect(jsonPath("$.refundableAmount.currency", equalTo("CNY")))
            .andExpect(jsonPath("$.refundableAmount.minorUnits", equalTo(0)));

        mockMvc.perform(post("/api/v1/post-sales-cases/{caseId}/approve", caseId)
                .header("Idempotency-Key", "cmd-test-approve-1")
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
                .header("Idempotency-Key", "cmd-test-invalid")
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
        MvcResult first = openCase("cmd-test-replay").andExpect(status().isCreated()).andReturn();
        openCase("cmd-test-replay")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.caseId", equalTo(first.getResponse().getContentAsString().split("\"caseId\":\"")[1].split("\"")[0])));
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
