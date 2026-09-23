package com.trainticket.bookingorchestration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.ChangeCompensationService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
public final class ChangeCompensationController {
    private final ChangeCompensationService service;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String base;

    public ChangeCompensationController(ChangeCompensationService service, RestClient.Builder builder, ObjectMapper mapper,
        @Value("${POST_SALES_BASE_URL:http://post-sales:8080}") String base) {
        this.service=service;this.mapper=mapper;this.base=base;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }

    @PostMapping("/api/v1/internal/change-compensations/{bookingId}")
    public BookingOrchestrationService.SegmentBookingDetail cancel(@PathVariable String bookingId, @RequestBody Request body,
        @RequestHeader("X-Correlation-Id") String correlation) {
        JsonNode change=client.get().uri(base+"/api/v1/post-sales-cases/"+body.caseId()).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("compensation case lookup returned "+response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
        return service.cancel(bookingId,body.caseId(),change,correlation);
    }

    public record Request(String caseId) {}
}
