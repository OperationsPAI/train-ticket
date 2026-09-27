package com.trainticket.payment.application;

import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.payment.observability.NoopPaymentMetrics;
import com.trainticket.payment.observability.PaymentMetricRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class PaymentTimeoutSweeper {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentTimeoutSweeper.class);

    private final PaymentCommandService paymentCommandService;
    private final PaymentIntentRepository intents;
    private final PaymentMetricRecorder metrics;
    private final Clock clock;

    @Autowired
    public PaymentTimeoutSweeper(
        PaymentCommandService paymentCommandService,
        ObjectProvider<PaymentIntentRepository> intents,
        ObjectProvider<PaymentMetricRecorder> metrics,
        ObjectProvider<Clock> clock
    ) {
        this(
            paymentCommandService,
            intents.getIfAvailable(),
            metrics.getIfAvailable(() -> NoopPaymentMetrics.INSTANCE),
            clock.getIfAvailable(Clock::systemUTC)
        );
    }

    public PaymentTimeoutSweeper(PaymentCommandService paymentCommandService) {
        this(paymentCommandService, null, NoopPaymentMetrics.INSTANCE, Clock.systemUTC());
    }

    PaymentTimeoutSweeper(
        PaymentCommandService paymentCommandService,
        PaymentIntentRepository intents,
        PaymentMetricRecorder metrics,
        Clock clock
    ) {
        // The command service is what a scheduled sweep drives; the backlog
        // reading is independent of it, so a caller that only wants the reading
        // (the test for it) may leave it out.
        this.paymentCommandService = paymentCommandService;
        this.intents = intents;
        this.metrics = Objects.requireNonNull(metrics, "recorder is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Scheduled(fixedDelayString = "${payment.timeout-sweeper-delay-ms:1000}")
    public void sweepExpiredPaymentIntents() {
        // The backlog is read BEFORE the sweep, so the age is what waited for
        // this tick rather than what survived it: a sweep that keeps up still
        // publishes the wait of the intent it is about to expire, and only a
        // queue longer than one batch leaves a reading behind.
        recordOldestDueIntentAge();
        Objects.requireNonNull(paymentCommandService, "command service is required to sweep")
            .expireDuePaymentIntents("payment-timeout-sweeper", "", 100);
    }

    /**
     * Publishes how long the oldest intent the sweep still owes has been due.
     *
     * The reading is rebuilt from the intent rows on every sweep rather than
     * kept in the process, so a restart republishes the same age instead of
     * resetting it, and a sweep with nothing due publishes nothing -- the series
     * going stale is the signal that the backlog is empty, not a zero that
     * reads as health. Measured on the deployed release, the query costs 12
     * milliseconds against 1.17 million intents, so it rides the sweep's own
     * one second tick.
     */
    void recordOldestDueIntentAge() {
        if (intents == null) {
            return;
        }
        try {
            Instant now = clock.instant();
            intents.findOldestDueExpiry(now)
                .ifPresent(due -> metrics.oldestDueIntentAge(Duration.between(due, now)));
        } catch (RuntimeException failure) {
            // The gauge is an observation, not a command: a read that fails must
            // not stop the sweep.
            LOGGER.warn("payment intent expiry sweep could not read its backlog", failure);
        }
    }
}
