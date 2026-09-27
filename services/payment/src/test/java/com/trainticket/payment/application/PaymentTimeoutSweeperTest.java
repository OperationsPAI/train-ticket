package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.observability.RecordingPaymentMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The expiry sweep publishes its own backlog.
 *
 * <p>Nothing published whether the one-second intent-expiry sweep was keeping
 * up: an intent past its expiry is money a customer may still be authorized
 * against, and the only durable evidence of the wait was the row itself. The
 * reading is rebuilt from those rows on every sweep, so it is the backlog rather
 * than a process counter -- and a sweep with nothing due publishes nothing.
 */
class PaymentTimeoutSweeperTest {
    private static final Instant NOW = Instant.parse("2026-07-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void anEmptyBacklogPublishesNothingRatherThanAZero() {
        InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        RecordingPaymentMetrics metrics = new RecordingPaymentMetrics();
        PaymentTimeoutSweeper sweeper = new PaymentTimeoutSweeper(null, intents, metrics, CLOCK);

        sweeper.recordOldestDueIntentAge();

        assertEquals(java.util.Optional.empty(), metrics.oldestDueIntentAge(),
            "a sweep with nothing due publishes no point at all");
    }

    @Test
    void theAgeIsTheOldestIntentTheSweepStillOwes() {
        InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        intents.save(intent("pi-late", NOW.minus(Duration.ofMinutes(9))));
        intents.save(intent("pi-newer", NOW.minus(Duration.ofMinutes(2))));
        RecordingPaymentMetrics metrics = new RecordingPaymentMetrics();
        PaymentTimeoutSweeper sweeper = new PaymentTimeoutSweeper(null, intents, metrics, CLOCK);

        sweeper.recordOldestDueIntentAge();

        assertEquals(java.util.Optional.of(Duration.ofMinutes(9)), metrics.oldestDueIntentAge());
    }

    private static PaymentIntent intent(String id, Instant expiresAt) {
        PaymentIntent intent = PaymentIntent.create(
            "ord-late-1",
            "purchase",
            com.trainticket.payment.domain.Money.of("12.00", "CNY"),
            "acc-late-1",
            expiresAt,
            "idem-" + id,
            NOW.minus(Duration.ofHours(1)),
            "cmd-late-1",
            "corr-late-1"
        );
        return intent;
    }
}
