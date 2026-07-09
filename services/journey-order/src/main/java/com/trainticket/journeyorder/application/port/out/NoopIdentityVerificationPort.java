package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(IdentityVerificationPort.class)
public class NoopIdentityVerificationPort implements IdentityVerificationPort {
    @Override
    public PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId) {
        return new PreOrderCheckResult("PASS", "poc-disabled");
    }
}
