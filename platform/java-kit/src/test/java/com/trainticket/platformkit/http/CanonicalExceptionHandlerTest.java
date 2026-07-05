package com.trainticket.platformkit.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CanonicalExceptionHandlerTest {
    @Test
    void mapsIdempotencyKeyReuseToCanonical422Body() {
        var response = new CanonicalExceptionHandlerSupport(null)
            .response(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", response.getBody().code());
        assertEquals("Idempotency-Key reused", response.getBody().message());
    }

    @Test
    void adviceUsesCanonicalIdempotencyMapping() {
        var response = new CanonicalExceptionHandler(null)
            .idempotencyKeyReused(new IdempotencyKeyReusedException("Idempotency-Key reused"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", response.getBody().code());
    }
}
