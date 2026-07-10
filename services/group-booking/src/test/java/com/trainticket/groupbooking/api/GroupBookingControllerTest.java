package com.trainticket.groupbooking.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainticket.groupbooking.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class GroupBookingControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthRespondsOk() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service.serviceId").value("group-booking"));
    }

    @Test
    void createValidatesMinimumGroupSize() throws Exception {
        mockMvc.perform(post("/api/v1/group-bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidSmallGroupRequest()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exposesUnversionedGroupBookingEndpoint() throws Exception {
        mockMvc.perform(post("/group-bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidSmallGroupRequest()))
            .andExpect(status().isBadRequest());
    }

    private static String invalidSmallGroupRequest() {
        return """
            {
              "organizerRef":"org-1",
              "segmentRefs":["seg-1"],
              "targetTravelerCount":9,
              "fare":{"currency":"USD","minorUnits":10000,"discountBasisPoints":0}
            }
            """;
    }
}
