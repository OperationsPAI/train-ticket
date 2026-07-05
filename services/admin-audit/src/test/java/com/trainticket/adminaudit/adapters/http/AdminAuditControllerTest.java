package com.trainticket.adminaudit.adapters.http;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "ADMIN_AUDIT_REDIS_ENABLED=false")
@AutoConfigureMockMvc
class AdminAuditControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void registerOperatorHappyPathAndGetOperator() throws Exception {
        MvcResult result = register("ops@example.com", "key-register-1")
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-test-1"))
            .andExpect(jsonPath("$.operatorId", startsWith("op-")))
            .andExpect(jsonPath("$.email").value("ops@example.com"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        String operatorId = body.substring(body.indexOf("op-"), body.indexOf("op-") + 39);

        mockMvc.perform(get("/api/v1/admin/operators/{operatorId}", operatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operatorId").value(operatorId));
    }

    @Test
    void manualActionHappyPathsAndAuditTrail() throws Exception {
        String requester = operatorId(register("requester@example.com", "key-register-2").andReturn());
        String approver = operatorId(register("approver@example.com", "key-register-3").andReturn());

        MvcResult action = mockMvc.perform(post("/api/v1/admin/manual-actions")
                .header("Idempotency-Key", "key-manual-1")
                .header("X-Correlation-Id", "corr-test-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"targetDomain":"payment","targetCommand":"RefundPayment","businessRef":"pi-1","reasonCode":"CUSTOMER_REQUEST","description":"refund","requestedByOperatorId":"%s","requiresApproval":true}
                    """.formatted(requester)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.manualActionId", startsWith("ma-")))
            .andExpect(jsonPath("$.targetDomain").value("payment"))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn();

        String manualActionId = actionId(action);

        mockMvc.perform(post("/api/v1/admin/manual-actions/{manualActionId}/approve", manualActionId)
                .header("Idempotency-Key", "key-approve-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approvedByOperatorId\":\"%s\"}".formatted(approver)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.manualActionId").value(manualActionId))
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/admin/audit-trail").param("businessRef", "pi-1").param("limit", "20").param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].resourceRef").value("pi-1"));
    }

    @Test
    void validationFailureUsesCanonicalBody() throws Exception {
        mockMvc.perform(post("/api/v1/admin/operators")
                .header("Idempotency-Key", "key-validation-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"scopes\":[\"OPERATOR_WRITE\"],\"displayName\":\"Ops\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.correlationId").exists())
            .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void idempotentReplayReturnsOriginalAndDifferentBodyIsRejected() throws Exception {
        MvcResult first = register("replay@example.com", "key-replay-1").andReturn();
        String firstBody = first.getResponse().getContentAsString();

        MvcResult replay = register("replay@example.com", "key-replay-1")
            .andExpect(status().isCreated())
            .andReturn();
        org.assertj.core.api.Assertions.assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);

        mockMvc.perform(post("/api/v1/admin/operators")
                .header("Idempotency-Key", "key-replay-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(operatorJson("different@example.com")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/operators")
            .header("Idempotency-Key", key)
            .header("X-Correlation-Id", "corr-test-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(operatorJson(email)));
    }

    private static String operatorJson(String email) {
        return """
            {"email":"%s","role":"ADMIN","scopes":["OPERATOR_WRITE","AUDIT_READ"],"displayName":"Ops"}
            """.formatted(email);
    }

    private static String operatorId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.substring(body.indexOf("op-"), body.indexOf("op-") + 39);
    }

    private static String actionId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.substring(body.indexOf("ma-"), body.indexOf("ma-") + 39);
    }
}
