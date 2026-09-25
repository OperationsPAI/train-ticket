package com.trainticket.payment.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refreshes the conservation gauge from durable payment records.
 *
 * <p>The gauge is not derived from the operation counters: those reset with the
 * process, and a reset must never look like an owner returning money. This
 * service re-reads the authoritative totals on a timer, so a restarted process
 * republishes the same balance once its first query succeeds. A failed refresh
 * keeps the previous reading rather than reporting a zero balance; before the
 * first successful query there is simply no series, which the rules treat as
 * unknown.
 *
 * <p>The refresh runs on its own daemon thread with a startup delay, never on
 * Spring's single shared scheduler: the conservation scan reads large snapshot
 * tables, and the payment timeout sweeper shares that scheduler on a one second
 * tick. Blocking it would delay intent expiry, which is a user-visible change.
 * The gauge keeps republishing the last reading between refreshes, so the
 * interval only bounds how stale the total can be.
 */
@Component
public class PaymentConservationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentConservationService.class);

    private final PaymentConservationLedger ledger;
    private final PaymentMetricRecorder recorder;
    private final long initialDelayMillis;
    private final long refreshIntervalMillis;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "payment-conservation-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile List<PaymentConservationPoint> current = List.of();

    public PaymentConservationService(
        PaymentConservationLedger ledger,
        PaymentMetricRecorder recorder,
        @Value("${payment.conservation-initial-delay-ms:120000}") long initialDelayMillis,
        @Value("${payment.conservation-refresh-ms:600000}") long refreshIntervalMillis
    ) {
        this.ledger = ledger;
        this.recorder = recorder;
        this.initialDelayMillis = initialDelayMillis;
        this.refreshIntervalMillis = refreshIntervalMillis;
    }

    @PostConstruct
    void start() {
        refresher.scheduleWithFixedDelay(
            this::refresh,
            Math.max(initialDelayMillis, 0),
            Math.max(refreshIntervalMillis, 1_000),
            TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        refresher.shutdownNow();
    }

    public void refresh() {
        try {
            List<CurrencyConservation> totals = ledger.totalsByCurrency();
            List<PaymentConservationPoint> points = PaymentConservationProjection.project(totals);
            current = points;
            recorder.conservation(points);
        } catch (RuntimeException failure) {
            LOGGER.warn("payment conservation refresh failed; keeping the previous reading", failure);
        }
    }

    public List<PaymentConservationPoint> current() {
        return current;
    }
}
