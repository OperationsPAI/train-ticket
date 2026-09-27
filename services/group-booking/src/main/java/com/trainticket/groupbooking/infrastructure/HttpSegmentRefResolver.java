package com.trainticket.groupbooking.infrastructure;

import com.trainticket.groupbooking.application.SegmentRefResolver;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks service-plan whether a segment exists. */
@Component
public class HttpSegmentRefResolver implements SegmentRefResolver {
    private final RestClient client;

    public HttpSegmentRefResolver(RestClient.Builder builder,
                                  @Value("${SERVICE_PLAN_URL:http://service-plan:8080}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).baseUrl(baseUrl).build();
    }

    @Override
    public boolean exists(String segmentRef) {
        if (segmentRef == null || segmentRef.isBlank()) return false;
        try {
            var response = client.get()
                .uri("/api/v1/service-segments/{id}", segmentRef)
                .retrieve()
                .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException notFound) {
            return false;
        }
    }
}
