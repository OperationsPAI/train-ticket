package collector

import (
	"context"
	"testing"
	"time"

	"go.opentelemetry.io/otel/attribute"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

func collect(t *testing.T, reader metricsdk.Reader, name string) metricdata.Metrics {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name == name {
				return metric
			}
		}
	}
	t.Fatalf("metric %s was not collected", name)
	return metricdata.Metrics{}
}

func attributeKeys(attributes attribute.Set) []string {
	keys := make([]string, 0, attributes.Len())
	for _, keyValue := range attributes.ToSlice() {
		keys = append(keys, string(keyValue.Key))
	}
	return keys
}

func TestExportedTransportMetricsCarryOnlyBoundedLabels(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })
	store := &Store{}
	if _, err := RegisterMetrics(provider.Meter("test"), store); err != nil {
		t.Fatalf("register: %v", err)
	}
	store.Set(Snapshot{
		Groups: []GroupState{
			{Stream: "events:journey-order", ConsumerGroup: "booking-orchestration", Lag: 4, Pending: 2, OldestAge: 90 * time.Second, HasOldest: true},
			{Stream: "events:payment", ConsumerGroup: "notification", Lag: 0, Pending: 0, OldestAge: time.Second, HasOldest: true},
		},
		DeadLetters: []DeadLetterState{
			{Stream: "events:payment", ConsumerGroup: "notification", EventType: "PaymentCaptured", Entries: 3},
		},
	})

	lag := collect(t, reader, MetricConsumerLag)
	lagData, ok := lag.Data.(metricdata.Gauge[int64])
	if !ok {
		t.Fatalf("lag must be an int64 gauge, got %T", lag.Data)
	}
	if len(lagData.DataPoints) != 2 {
		t.Fatalf("expected one series per consumer group, got %d", len(lagData.DataPoints))
	}
	// Two different streams must produce the same label keys with different
	// values: that is the cardinality rule, and it is what stops a business id
	// from ever reaching a label set.
	first := attributeKeys(lagData.DataPoints[0].Attributes)
	second := attributeKeys(lagData.DataPoints[1].Attributes)
	if len(first) != 2 || len(second) != 2 || first[0] != second[0] || first[1] != second[1] {
		t.Fatalf("label keys must match across streams: %v vs %v", first, second)
	}

	oldest := collect(t, reader, MetricConsumerOldestAge)
	oldestData, ok := oldest.Data.(metricdata.Gauge[float64])
	if !ok {
		t.Fatalf("oldest age must be a float gauge, got %T", oldest.Data)
	}
	if oldest.Unit != "s" {
		t.Fatalf("oldest age unit = %q, want the seconds unit the recording rule expects", oldest.Unit)
	}
	for _, point := range oldestData.DataPoints {
		if point.Value < 0 {
			t.Fatalf("an age must never be negative: %+v", point)
		}
	}

	deadLetters := collect(t, reader, MetricDLQEntries)
	deadLetterData, ok := deadLetters.Data.(metricdata.Gauge[int64])
	if !ok {
		t.Fatalf("dead letters must be an int64 gauge, got %T", deadLetters.Data)
	}
	if len(deadLetterData.DataPoints) != 1 || deadLetterData.DataPoints[0].Value != 3 {
		t.Fatalf("unexpected dead-letter series: %+v", deadLetterData.DataPoints)
	}
	if keys := attributeKeys(deadLetterData.DataPoints[0].Attributes); len(keys) != 3 {
		t.Fatalf("dead letters must carry exactly stream, group and event type: %v", keys)
	}
}

func TestGaugesGoStaleInsteadOfReadingZeroBeforeTheFirstPoll(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })
	store := &Store{}
	if _, err := RegisterMetrics(provider.Meter("test"), store); err != nil {
		t.Fatalf("register: %v", err)
	}

	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			if metric.Name != MetricConsumerLag {
				continue
			}
			if points := metric.Data.(metricdata.Gauge[int64]).DataPoints; len(points) != 0 {
				t.Fatalf("a collector that has not polled must report nothing, got %+v", points)
			}
			return
		}
	}
	// The instrument itself is not collected when it has never observed
	// anything, which is the outcome this test wants: a lag of zero and a
	// collector that has not read anything must not look the same.
}
