package com.trainticket.platformkit.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

class PlatformKitExceptionHandlerTest {
    @Test
    void mapsIdempotencyReuseToCanonical422() {
        PlatformKitExceptionHandler handler = new PlatformKitExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler.handleIdempotencyReuse(new IdempotencyKeyReusedException(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(response.getBody().correlationId()).isNotBlank();
    }
}
