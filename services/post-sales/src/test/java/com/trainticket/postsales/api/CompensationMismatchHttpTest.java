package com.trainticket.postsales.api;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.CompensationMismatchCheckpoint;
import com.trainticket.postsales.application.CompensationMismatchException;
import com.trainticket.postsales.application.CompensationExecutionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CompensationMismatchHttpTest {
    @Test
    void returnsStableConflictThatForbidsAutomaticReplay() throws Exception {
        var mapper=new ObjectMapper();
        var checkpoint=new CompensationMismatchCheckpoint("case-1","ord-1","APPLIED","WALLET_CREDIT_MISSING",
            "applied compensation has no wallet credit","CNY",1000,mapper.createObjectNode(),mapper.createObjectNode(),
            mapper.createObjectNode(),mapper.createObjectNode(),"corr-original",Instant.parse("2026-09-25T04:00:00Z"));
        CompensationExecutionService service=mock(CompensationExecutionService.class);
        when(service.advance(anyString(),anyString())).thenThrow(new CompensationMismatchException(checkpoint));
        var controller=new CompensationExecutionController(service);
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new PostSalesExceptionHandler()).build();
        mvc.perform(post("/api/v1/post-sales-cases/psc-case-1/execute-compensation").header("X-Correlation-Id","corr-replay"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code",equalTo("COMPENSATION_MISMATCH")))
            .andExpect(jsonPath("$.details.caseId",equalTo("psc-case-1")))
            .andExpect(jsonPath("$.details.mismatchCode",equalTo("WALLET_CREDIT_MISSING")))
            .andExpect(jsonPath("$.details.approvedAmount.minorUnits",equalTo(1000)))
            .andExpect(jsonPath("$.details.automaticReplayAllowed",equalTo(false)));
    }
}
