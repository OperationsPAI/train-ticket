package com.trainticket.marketingcampaign.api;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainticket.marketingcampaign.Application;
import com.trainticket.platformkit.idempotency.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class CampaignControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthRespondsOk() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service.serviceId").value("marketing-campaign"));
    }

    @Test
    void draftCampaignCreatesReadableResource() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/campaigns")
                .header("Idempotency-Key", UuidV7.generate())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "externalKey":"summer-2026",
                      "name":"Summer sale",
                      "window":{"validFrom":"2026-06-01T00:00:00Z","validUntil":"2026-09-01T00:00:00Z"}
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.campaignId", startsWith("mc-")))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.campaignId");
        mockMvc.perform(get("/api/v1/campaigns/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.externalKey").value("summer-2026"));
    }

    @Test
    void draftCampaignRequiresWindow() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns")
                .header("Idempotency-Key", UuidV7.generate())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalKey\":\"bad\",\"name\":\"Bad\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }
}
