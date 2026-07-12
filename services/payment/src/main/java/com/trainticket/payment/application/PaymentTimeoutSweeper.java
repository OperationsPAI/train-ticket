package com.trainticket.payment.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class PaymentTimeoutSweeper {
    private final PaymentCommandService paymentCommandService;

    public PaymentTimeoutSweeper(PaymentCommandService paymentCommandService) {
        this.paymentCommandService = paymentCommandService;
    }

    @Scheduled(fixedDelayString = "${payment.timeout-sweeper-delay-ms:1000}")
    public void sweepExpiredPaymentIntents() {
        paymentCommandService.expireDuePaymentIntents("payment-timeout-sweeper", "", 100);
    }
}
