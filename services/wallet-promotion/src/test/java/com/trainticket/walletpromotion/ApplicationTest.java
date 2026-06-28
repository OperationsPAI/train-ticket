package com.trainticket.walletpromotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationTest {
    @Test
    void healthEndpointReturnsServiceProfile() {
        Map<String, Object> response = new HealthController().health();

        assertEquals("ok", response.get("status"));
        ServiceProfile profile = assertInstanceOf(ServiceProfile.class, response.get("service"));
        assertEquals("wallet-promotion", profile.serviceId());
    }
}
