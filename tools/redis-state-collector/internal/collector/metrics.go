package collector

import (
	"context"
	"fmt"
	"os"
	"sort"
	"strconv"
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
	// MetricSeries is this collector's own cardinality. It is the guard the
	// other four need: their label set is the transport's own stream and group
	// names, so a service that starts one consumer group per pod multiplies the
	// series without changing any of the values a reader would notice.
	MetricSeries = "train_ticket.event.transport.series"
)

// Series bounds. A reader queries the transport by stream and group, so the
// label set is as wide as the transport is; these bounds are deliberately far
// above the deployed shape (about forty streams and two hundred groups) and far
// below anything Prometheus should be asked to hold.
const (
	defaultMaxGroupSeries      = 400
	defaultMaxDeadLetterSeries = 400
)

// SeriesBounds are the maxima the collector will publish, and the environment
// variables that override them.
type SeriesBounds struct {
	Groups      int
	DeadLetters int
}

// SeriesBoundsFromEnv reads the bounds, falling back to the defaults for a
// blank, unparseable or non-positive value: a misconfigured cap that silently
// dropped every series would be the worst outcome here.
func SeriesBoundsFromEnv() SeriesBounds {
	return SeriesBounds{
		Groups:      positiveEnv("TRANSPORT_MAX_GROUP_SERIES", defaultMaxGroupSeries),
		DeadLetters: positiveEnv("TRANSPORT_MAX_DEAD_LETTER_SERIES", defaultMaxDeadLetterSeries),
	}
}

func positiveEnv(name string, fallback int) int {
	configured := os.Getenv(name)
	if configured == "" {
		return fallback
	}
	// A bound of zero would publish nothing at all, which reads as "the
	// transport is idle" -- the one thing this collector must never say by
	// accident.
	if parsed, err := strconv.Atoi(configured); err == nil && parsed > 0 {
		return parsed
	}
	fmt.Fprintf(os.Stderr, "redis-state-collector: %s=%q is not a positive integer; using %d\n", name, configured, fallback)
	return fallback
}

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

// RegisterMetrics binds the transport gauges to the last poll.
//
// A failed poll publishes nothing new, so the gauges keep their previous values
// until they go stale. That is deliberate: "the collector could not read Redis"
// and "the transport is completely idle" must not produce the same series.
//
// The four transport gauges are bounded by `bounds`, and what the bound holds
// back is published as its own series rather than dropped in silence. The order
// is sorted first, so a truncation is the same set of streams on every poll
// instead of whichever ones Redis happened to return first.
func RegisterMetrics(meter metric.Meter, store *Store, bounds SeriesBounds) (metric.Registration, error) {
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
	series, err := meter.Int64ObservableGauge(MetricSeries,
		metric.WithDescription("Series this collector publishes by kind, including what its own bound holds back."),
		metric.WithUnit("{series}"),
	)
	if err != nil {
		return nil, err
	}

	return meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		snapshot, ok := store.Latest()
		if !ok {
			return nil
		}
		groups := append([]GroupState(nil), snapshot.Groups...)
		sort.Slice(groups, func(left, right int) bool {
			if groups[left].Stream != groups[right].Stream {
				return groups[left].Stream < groups[right].Stream
			}
			return groups[left].ConsumerGroup < groups[right].ConsumerGroup
		})
		published := bound(bounds.Groups, len(groups))
		for _, group := range groups[:published] {
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
		letters := append([]DeadLetterState(nil), snapshot.DeadLetters...)
		sort.Slice(letters, func(left, right int) bool {
			if letters[left].Stream != letters[right].Stream {
				return letters[left].Stream < letters[right].Stream
			}
			if letters[left].ConsumerGroup != letters[right].ConsumerGroup {
				return letters[left].ConsumerGroup < letters[right].ConsumerGroup
			}
			return letters[left].EventType < letters[right].EventType
		})
		publishedLetters := bound(bounds.DeadLetters, len(letters))
		for _, deadLetter := range letters[:publishedLetters] {
			observer.ObserveInt64(deadLetters, deadLetter.Entries, metric.WithAttributes(
				attribute.String("event_stream", deadLetter.Stream),
				attribute.String("consumer_group", deadLetter.ConsumerGroup),
				attribute.String("event_type", deadLetter.EventType),
			))
		}
		for kind, count := range map[string]int{
			"group":               published,
			"group_dropped":       len(groups) - published,
			"dead_letter":         publishedLetters,
			"dead_letter_dropped": len(letters) - publishedLetters,
		} {
			observer.ObserveInt64(series, int64(count), metric.WithAttributes(attribute.String("kind", kind)))
		}
		return nil
	}, lag, pending, oldestAge, deadLetters, series)
}

// bound is the number of series to publish out of `total`.
func bound(maximum, total int) int {
	if maximum > 0 && total > maximum {
		return maximum
	}
	return total
}
