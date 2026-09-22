package com.trainticket.journeyorder.infrastructure.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.platformkit.idempotency.UuidV7;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

class LiveIdentityTransitionTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_IDENTITY_URL", matches=".+")
    void missingIdentityTransitionsAreRejected() {
        var adapter = new HttpIdentityVerificationAdapter(new ObjectMapper(), RestClient.builder(), System.getenv("TEST_IDENTITY_URL"));
        String checkId = "poc-" + UuidV7.generate();
        var confirm = assertThrows(ApiException.class, () -> adapter.confirmPreOrderCheck(checkId, "ord-" + UuidV7.generate(), UuidV7.generate(), "corr-" + UuidV7.generate()));
        assertEquals(ApiErrorCode.PRECONDITION_FAILED, confirm.code());
        var release = assertThrows(ApiException.class, () -> adapter.releasePreOrderCheck(checkId, "ORDER_CANCELLED", UuidV7.generate(), "corr-" + UuidV7.generate()));
        assertEquals(ApiErrorCode.PRECONDITION_FAILED, release.code());
    }
}
