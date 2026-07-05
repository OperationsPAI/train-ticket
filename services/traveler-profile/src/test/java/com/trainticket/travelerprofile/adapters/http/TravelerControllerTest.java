package com.trainticket.travelerprofile.adapters.http;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.travelerprofile.NoOpRuntimeTracer;
import com.trainticket.travelerprofile.RequestContextFilter;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.travelerprofile.application.EventPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MvcResult;

class TravelerControllerTest {
    MockMvc mockMvc;
    RecordingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        publisher = new RecordingEventPublisher();
        TravelerController controller = new TravelerController(
            new com.trainticket.travelerprofile.application.TravelerProfileService(publisher, objectMapper)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new RequestContextFilter(new NoOpRuntimeTracer()))
            .setControllerAdvice(new PlatformKitExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void createTravelerHappyPathAndReplayReturnsOriginalResult() throws Exception {
        String body = """
            {"accountId":"acc-1","travelerType":"ADULT","givenName":"Wei","familyName":"Zhang","documentType":"ID_CARD","documentNumber":"110101199001151234","contactEmail":"wei@example.test","contactPhone":"13800000000"}
            """;

        MvcResult first = mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c001")
                .header("X-Correlation-Id", "corr-018f0000-0000-7000-8000-000000000099")
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-018f0000-0000-7000-8000-000000000099"))
            .andExpect(jsonPath("$.travelerId", startsWith("tvl-")))
            .andExpect(jsonPath("$.snapshotVersion").value("sv-1"))
            .andExpect(jsonPath("$.travelerType").value("ADULT"))
            .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals("TravelerProfileUpdated", publisher.envelopes.getFirst().eventType());
        org.junit.jupiter.api.Assertions.assertEquals("traveler-profile", publisher.envelopes.getFirst().producer());
        org.junit.jupiter.api.Assertions.assertTrue(publisher.envelopes.stream().allMatch(envelope -> envelope.eventId().startsWith("evt-")));
        org.junit.jupiter.api.Assertions.assertTrue(publisher.envelopes.stream().allMatch(envelope -> envelope.causationId().startsWith("cmd-") || envelope.causationId().startsWith("evt-")));

        mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c001")
                .header("X-Correlation-Id", "corr-018f0000-0000-7000-8000-000000000099")
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.travelerId").value(Json.read(first, "$.travelerId")));
    }

    @Test
    void getPatchAndEligibilityHappyPaths() throws Exception {
        String travelerId = createTraveler();

        mockMvc.perform(get("/api/v1/travelers/{travelerId}", travelerId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.travelerId").value(travelerId))
            .andExpect(jsonPath("$.maskedDocumentRef").value("E1***5678"));

        mockMvc.perform(patch("/api/v1/travelers/{travelerId}", travelerId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c002")
                .content("{\"travelerType\":\"STUDENT\",\"contactPhone\":\"13900000000\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.travelerType").value("STUDENT"))
            .andExpect(jsonPath("$.snapshotVersion").value("sv-2"));

        mockMvc.perform(post("/api/v1/travelers/{travelerId}/eligibility", travelerId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c003")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.travelerId").value(travelerId))
            .andExpect(jsonPath("$.eligibilityRef", startsWith("elig-")))
            .andExpect(jsonPath("$.eligible").value(true));
    }

    @Test
    void validationFailureUsesCanonicalErrorShape() throws Exception {
        mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c004")
                .header("X-Correlation-Id", "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0e1")
                .content("{\"travelerType\":\"ADULT\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.correlationId").value("corr-0194f2e0-7b3e-7610-8284-5c26e8b0c0e1"))
            .andExpect(jsonPath("$.details.accountId").exists());
    }

    @Test
    void idempotencyKeyReuseWithDifferentBodyReturns422() throws Exception {
        String first = "{\"accountId\":\"acc-1\",\"travelerType\":\"ADULT\",\"givenName\":\"Wei\",\"familyName\":\"Zhang\"}";
        String second = "{\"accountId\":\"acc-1\",\"travelerType\":\"CHILD\",\"givenName\":\"Wei\",\"familyName\":\"Zhang\"}";
        mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c005")
                .content(first))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c005")
                .content(second))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void getUnknownTravelerReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/travelers/tvl-missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateDomainInvariantViolationSurfacesCanonicalDomainError() throws Exception {
        String travelerId = createTraveler();

        mockMvc.perform(patch("/api/v1/travelers/{travelerId}", travelerId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c007")
                .content("{\"documentType\":\"PASSPORT\",\"documentNumber\":\"E12345678\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"));
    }

    private String createTraveler() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/travelers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "0194f2e0-7b3e-7610-8284-5c26e8b0c006")
                .content("{\"accountId\":\"acc-2\",\"travelerType\":\"ADULT\",\"givenName\":\"Mei\",\"familyName\":\"Li\",\"documentType\":\"PASSPORT\",\"documentNumber\":\"E12345678\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return Json.read(result, "$.travelerId");
    }

    static class RecordingEventPublisher implements EventPublisher {
        final List<EventEnvelope> envelopes = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            envelopes.add(envelope);
        }
    }
}
