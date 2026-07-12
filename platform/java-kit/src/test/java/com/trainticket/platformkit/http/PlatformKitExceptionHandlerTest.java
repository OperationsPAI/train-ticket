package com.trainticket.platformkit.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.platformkit.PlatformKitConfiguration;
import com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class PlatformKitExceptionHandlerTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PlatformKitConfiguration.class));

    @Test
    void mapsIdempotencyReuseToCanonical422() {
        PlatformKitExceptionHandler handler = new PlatformKitExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler.handleIdempotencyReuse(new IdempotencyKeyReusedException(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(response.getBody().correlationId()).isNotBlank();
    }

    @Test
    void platformConfigurationRegistersExceptionHandlerBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PlatformKitExceptionHandler.class);

            PlatformKitExceptionHandler handler = context.getBean(PlatformKitExceptionHandler.class);
            ResponseEntity<ApiError> response = handler.handleIdempotencyReuse(
                new IdempotencyKeyReusedException(),
                new MockHttpServletRequest()
            );

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        });
    }
}

