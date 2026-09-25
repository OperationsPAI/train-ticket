package collector

import (
	"context"
	"sync"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// Metric names are the contract in docs/07-observability/business-metrics.md.
// The exporter adds a unit suffix, so the seconds unit here is what makes the
// recording rule's `..._oldest_age_seconds` name.
const (
	MetricConsumerLag       = "train_ticket.event.consumer.lag"
	MetricConsumerPending   = "train_ticket.event.consumer.pending"
	MetricConsumerOldestAge = "train_ticket.event.consumer.oldest_age"
	MetricDLQEntries        = "train_ticket.event.dlq.entries"
)

// Store is the hand-off between the poller and the metric callbacks: the
// metric callback is synchronous, so it reads whatever the poller last stored
// rather than touching Redis on the exporter's schedule.
type Store struct {
	mutex    sync.RWMutex
	snapshot Snapshot
	ok       bool
}

// Set publishes a completed poll.
func (store *Store) Set(snapshot Snapshot) {
	store.mutex.Lock()
	defer store.mutex.Unlock()
	store.snapshot = snapshot
	store.ok = true
}

// Latest returns the last completed poll, if there has been one.
func (store *Store) Latest() (Snapshot, bool) {
	store.mutex.RLock()
	defer store.mutex.RUnlock()
	return store.snapshot, store.ok
}

// RegisterMetrics binds the four transport gauges to the last poll.
//
// A failed poll publishes nothing new, so the gauges keep their previous values
// until they go stale. That is deliberate: "the collector could not read Redis"
// and "the transport is completely idle" must not produce the same series.
func RegisterMetrics(meter metric.Meter, store *Store) (metric.Registration, error) {
	lag, err := meter.Int64ObservableGauge(MetricConsumerLag,
		metric.WithDescription("Redis stream entries the consumer group is behind by."),
	)
	if err != nil {
		return nil, err
	}
	pending, err := meter.Int64ObservableGauge(MetricConsumerPending,
		metric.WithDescription("Redis stream entries delivered to the consumer group and not acknowledged."),
	)
	if err != nil {
		return nil, err
	}
	oldestAge, err := meter.Float64ObservableGauge(MetricConsumerOldestAge,
		metric.WithUnit("s"),
		metric.WithDescription("Age of the oldest entry the consumer group still owes."),
	)
	if err != nil {
		return nil, err
	}
	deadLetters, err := meter.Int64ObservableGauge(MetricDLQEntries,
		metric.WithDescription("Current dead-letter population by stream, consumer group and event type."),
	)
	if err != nil {
		return nil, err
	}

	return meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		snapshot, ok := store.Latest()
		if !ok {
			return nil
		}
		for _, group := range snapshot.Groups {
			attributes := metric.WithAttributes(
				attribute.String("event_stream", group.Stream),
				attribute.String("consumer_group", group.ConsumerGroup),
			)
			observer.ObserveInt64(lag, group.Lag, attributes)
			observer.ObserveInt64(pending, group.Pending, attributes)
			if group.HasOldest {
				observer.ObserveFloat64(oldestAge, group.OldestAge.Seconds(), attributes)
			}
		}
		for _, deadLetter := range snapshot.DeadLetters {
			observer.ObserveInt64(deadLetters, deadLetter.Entries, metric.WithAttributes(
				attribute.String("event_stream", deadLetter.Stream),
				attribute.String("consumer_group", deadLetter.ConsumerGroup),
				attribute.String("event_type", deadLetter.EventType),
			))
		}
		return nil
	}, lag, pending, oldestAge, deadLetters)
}
