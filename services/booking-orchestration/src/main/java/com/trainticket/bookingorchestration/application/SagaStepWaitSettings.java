package com.trainticket.bookingorchestration.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How long each planned saga step waits for the answer it was planned around.
 *
 * A step still pending when its wait is past is failed by the timeout sweeper, its compensation
 * action runs and the saga stops waiting, so the wait is the whole of the time the orchestrator
 * gives the rest of the platform to answer a step. The plans held these budgets as constants in
 * `startSagaAggregate`, which meant a deployment that wanted a different budget had to rebuild the
 * image; they are settings now, so the budgets sit beside the other knobs the release already sets
 * for this service. The defaults are the budgets the constants carried, and a deployment that sets
 * none of them gets exactly the behaviour the constants gave.
 */
@Component
public class SagaStepWaitSettings {

    /** The budget the risk step carried as a constant. */
    public static final long DEFAULT_RISK_CHECK_SECONDS = 120;

    /** The budget each reserve step carried as a constant. */
    public static final long DEFAULT_RESERVATION_SECONDS = 300;

    /** The budget the invoice step carried as a constant. */
    public static final long DEFAULT_INVOICE_SECONDS = 120;

    private final Duration riskCheck;
    private final Duration reservation;
    private final Duration invoice;

    public SagaStepWaitSettings(
            @Value("${booking-orchestration.step-wait.risk-check-seconds:120}") long riskCheckSeconds,
            @Value("${booking-orchestration.step-wait.reservation-seconds:300}") long reservationSeconds,
            @Value("${booking-orchestration.step-wait.invoice-seconds:120}") long invoiceSeconds) {
        this.riskCheck = wait("risk-check", riskCheckSeconds);
        this.reservation = wait("reservation", reservationSeconds);
        this.invoice = wait("invoice", invoiceSeconds);
    }

    /** The budgets the plans carried before they became settings, for callers with no environment. */
    public static SagaStepWaitSettings defaults() {
        return new SagaStepWaitSettings(DEFAULT_RISK_CHECK_SECONDS, DEFAULT_RESERVATION_SECONDS, DEFAULT_INVOICE_SECONDS);
    }

    private static Duration wait(String step, long seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("the " + step + " wait must be at least one second");
        }
        return Duration.ofSeconds(seconds);
    }

    public Duration riskCheck() {
        return riskCheck;
    }

    public Duration reservation() {
        return reservation;
    }

    public Duration invoice() {
        return invoice;
    }
}
