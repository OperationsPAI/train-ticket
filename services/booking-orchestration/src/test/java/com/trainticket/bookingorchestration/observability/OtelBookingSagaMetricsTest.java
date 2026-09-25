package com.trainticket.bookingorchestration.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OtelBookingSagaMetricsTest {
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");

    @Test
    void publishesTheThreeContractNamesWithOnlyTheirBoundedLabels() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            BookingSagaMetrics metrics = new OtelBookingSagaMetrics(provider.get("booking-saga-metrics-test"));
            metrics.trackActive(BookingSagaMetrics.Stage.PAYMENT, 2);
            metrics.observeOldestAge(BookingSagaMetrics.Stage.PAYMENT, Duration.ofSeconds(45));
            metrics.transition(BookingSagaMetrics.Stage.RESERVATION, BookingSagaMetrics.Outcome.REJECTED,
                BookingSagaMetrics.ReasonCode.PROVIDER_FAILURE);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            assertThat(exporter.getFinishedMetricItems())
                .extracting(MetricData::getName)
                .contains("train_ticket.booking.saga.active",
                    "train_ticket.booking.saga.oldest_age",
                    "train_ticket.booking.saga.transitions");

            assertThat(labelKeys(exporter, "train_ticket.booking.saga.active")).containsExactly("stage");
            assertThat(labelKeys(exporter, "train_ticket.booking.saga.oldest_age")).containsExactly("stage");
            assertThat(labelKeys(exporter, "train_ticket.booking.saga.transitions"))
                .containsExactly("outcome", "reason_code", "stage");
            assertThat(exporter.getFinishedMetricItems())
                .filteredOn(metric -> metric.getName().equals("train_ticket.booking.saga.oldest_age"))
                .singleElement()
                .satisfies(metric -> assertThat(metric.getUnit()).isEqualTo("s"));
        }
    }

    /**
     * The instrument API takes no saga id, so two different sagas at the same stage must land on the
     * same series. A leaked identifier would double the series count and fail here.
     */
    @Test
    void twoDifferentSagasCollapseOntoOneSeries() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            BookingSagaMetrics metrics = new OtelBookingSagaMetrics(provider.get("booking-saga-metrics-test"));
            metrics.trackActive(BookingSagaMetrics.Stage.PAYMENT, 2);
            metrics.transition(BookingSagaMetrics.Stage.PAYMENT, BookingSagaMetrics.Outcome.ACCEPTED,
                BookingSagaMetrics.ReasonCode.NONE);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            assertThat(activeValue(exporter, "payment")).isEqualTo(2L);
            assertThat(exporter.getFinishedMetricItems())
                .filteredOn(metric -> metric.getName().equals("train_ticket.booking.saga.transitions"))
                .singleElement()
                .satisfies(metric -> assertThat(metric.getLongSumData().getPoints()).hasSize(1));
        }
    }

    @Test
    void unknownVocabularyCollapsesToOtherInsteadOfLeakingItsText() {
        assertThat(BookingSagaMetrics.Stage.fromValue("payment")).isEqualTo(BookingSagaMetrics.Stage.PAYMENT);
        assertThat(BookingSagaMetrics.Stage.fromValue("saga-9f2c")).isEqualTo(BookingSagaMetrics.Stage.OTHER);
        assertThat(BookingSagaMetrics.Outcome.fromValue("card declined: insufficient funds"))
            .isEqualTo(BookingSagaMetrics.Outcome.OTHER);
        assertThat(BookingSagaMetrics.ReasonCode.fromValue("provider said: card declined for saga-9f2c"))
            .isEqualTo(BookingSagaMetrics.ReasonCode.OTHER);
        assertThat(BookingSagaMetrics.ReasonCode.fromValue("provider_failure"))
            .isEqualTo(BookingSagaMetrics.ReasonCode.PROVIDER_FAILURE);
    }

    /**
     * The active level is an UpDownCounter driven by the durable level: it rises when a saga starts
     * waiting at a stage and falls when one leaves, with no in-memory ledger of sagas.
     */
    @Test
    void activeRisesAndFallsWithTheDurableLevel() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            BookingSagaMetrics metrics = new OtelBookingSagaMetrics(provider.get("booking-saga-metrics-test"));
            metrics.trackActive(BookingSagaMetrics.Stage.RESERVATION, 3);
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            assertThat(activeValue(exporter, "reservation")).isEqualTo(3L);

            metrics.trackActive(BookingSagaMetrics.Stage.RESERVATION, 1);
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            assertThat(activeValue(exporter, "reservation")).isEqualTo(1L);

            metrics.trackActive(BookingSagaMetrics.Stage.RESERVATION, 0);
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            assertThat(activeValue(exporter, "reservation")).isEqualTo(0L);
        }
    }

    /**
     * A restart cannot reset the age to zero: a second, independent process handed only the durable
     * saga rows rebuilds the same levels the first process reported.
     */
    @Test
    void levelsAreRebuiltFromStoredStateAcrossARestart() {
        java.time.Instant start = java.time.Instant.parse("2026-09-25T00:00:00Z");
        java.time.Instant now = start.plusSeconds(80);
        List<BookingSagaStageRepository.ActiveSaga> stored = List.of(
            new BookingSagaStageRepository.ActiveSaga("saga-1", "RISK_CHECKING", start),
            new BookingSagaStageRepository.ActiveSaga("saga-2", "AWAITING_PAYMENT", start.plusSeconds(30)),
            new BookingSagaStageRepository.ActiveSaga("saga-3", "AWAITING_PAYMENT", start.plusSeconds(50)));

        double[] beforeRestart;
        InMemoryMetricExporter firstExporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(firstExporter)) {
            publish(new OtelBookingSagaMetrics(provider.get("booking-saga-metrics-test")), stored, now);
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            beforeRestart = new double[] {
                activeValue(firstExporter, "payment"),
                oldestAgeSeconds(firstExporter, "payment"),
                activeValue(firstExporter, "risk_check"),
                oldestAgeSeconds(firstExporter, "risk_check")};
        }

        InMemoryMetricExporter restartedExporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(restartedExporter)) {
            publish(new OtelBookingSagaMetrics(provider.get("booking-saga-metrics-test")), stored, now);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            assertThat(activeValue(restartedExporter, "payment")).isEqualTo(2L);
            // The older of the two payment waits is the one stamped at START+30, so its age is 50s.
            assertThat(oldestAgeSeconds(restartedExporter, "payment")).isEqualTo(50.0);
            assertThat(activeValue(restartedExporter, "risk_check")).isEqualTo(1L);
            assertThat(oldestAgeSeconds(restartedExporter, "risk_check")).isEqualTo(80.0);
            assertThat(new double[] {
                activeValue(restartedExporter, "payment"),
                oldestAgeSeconds(restartedExporter, "payment"),
                activeValue(restartedExporter, "risk_check"),
                oldestAgeSeconds(restartedExporter, "risk_check")})
                .containsExactly(beforeRestart);
        }
    }

    private static void publish(BookingSagaMetrics metrics, List<BookingSagaStageRepository.ActiveSaga> stored,
                                java.time.Instant now) {
        for (var entry : BookingSagaStageProjection.project(stored, now).entrySet()) {
            metrics.trackActive(entry.getKey(), entry.getValue().activeSagas());
            metrics.observeOldestAge(entry.getKey(), entry.getValue().oldestAge());
        }
    }

    private static long activeValue(InMemoryMetricExporter exporter, String stage) {
        return exporter.getFinishedMetricItems().stream()
            .filter(metric -> metric.getName().equals("train_ticket.booking.saga.active"))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .filter(point -> stage.equals(point.getAttributes().get(STAGE)))
            .reduce((first, second) -> second)
            .orElseThrow()
            .getValue();
    }

    private static double oldestAgeSeconds(InMemoryMetricExporter exporter, String stage) {
        return exporter.getFinishedMetricItems().stream()
            .filter(metric -> metric.getName().equals("train_ticket.booking.saga.oldest_age"))
            .flatMap(metric -> metric.getDoubleGaugeData().getPoints().stream())
            .filter(point -> stage.equals(point.getAttributes().get(STAGE)))
            .reduce((first, second) -> second)
            .orElseThrow()
            .getValue();
    }

    private static List<String> labelKeys(InMemoryMetricExporter exporter, String name) {
        return exporter.getFinishedMetricItems().stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getData().getPoints().stream())
            .flatMap(point -> point.getAttributes().asMap().keySet().stream())
            .map(AttributeKey::getKey)
            .distinct()
            .sorted()
            .toList();
    }

    private static SdkMeterProvider provider(InMemoryMetricExporter exporter) {
        return SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build();
    }
}
