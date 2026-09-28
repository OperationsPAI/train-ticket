package com.trainticket.bookingorchestration.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How long each planned saga step waits for the answer it was planned around.
 *
 * <p>A step that is still pending when its wait is past is failed by the timeout sweep, its
 * compensation action runs and the saga stops waiting, so the wait is the whole of the time the
 * orchestrator gives the rest of the platform to answer a step. The plans carried these budgets as
 * constants, which meant a deployment that wanted a different one rebuilt the image; they are
 * settings now, stated beside the other knobs the release already sets for this service. The
 * defaults are the budgets the constants carried, so a deployment that sets none of them gets what
 * it got before.
 */
@Component
public class BookingStepWaitSettings {

    /** The budget the risk check's step carried as a constant. */
    static final long DEFAULT_RISK_CHECK_SECONDS = 120;

    /** The budget each reserve step carried as a constant. */
    static final long DEFAULT_RESERVATION_SECONDS = 300;

    /** The budget the invoice step carried as a constant. */
    static final long DEFAULT_INVOICE_SECONDS = 120;

    /** The budget the change flow's replacement reservation carried as a constant. */
    static final long DEFAULT_CHANGE_RESERVATIONS_SECONDS = 900;

    private final Duration riskCheck;
    private final Duration reservation;
    private final Duration invoice;
    private final Duration changeReservations;

    public BookingStepWaitSettings(
            @Value("${booking-orchestration.step-wait.risk-check-seconds:120}") long riskCheckSeconds,
            @Value("${booking-orchestration.step-wait.reservation-seconds:300}") long reservationSeconds,
            @Value("${booking-orchestration.step-wait.invoice-seconds:120}") long invoiceSeconds,
            @Value("${booking-orchestration.step-wait.change-reservations-seconds:900}") long changeReservationsSeconds) {
        this.riskCheck = wait("risk check", riskCheckSeconds);
        this.reservation = wait("reservation", reservationSeconds);
        this.invoice = wait("invoice", invoiceSeconds);
        this.changeReservations = wait("change reservations", changeReservationsSeconds);
    }

    /** The budgets the plans carried before they became settings, for callers with no environment. */
    public static BookingStepWaitSettings defaults() {
        return new BookingStepWaitSettings(DEFAULT_RISK_CHECK_SECONDS, DEFAULT_RESERVATION_SECONDS,
            DEFAULT_INVOICE_SECONDS, DEFAULT_CHANGE_RESERVATIONS_SECONDS);
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

    public Duration changeReservations() {
        return changeReservations;
    }
}
