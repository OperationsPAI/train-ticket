package storage

import (
	"context"
	"log"
	"sync"
	"sync/atomic"
	"time"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// OutboxStage is the bounded part of the relay a failure happened in. The three
// stages are the three things the relay does, so a failing select, a failing
// publish and a failing acknowledgement stay distinguishable without naming the
// statement that failed.
type OutboxStage string

const (
	// StageSelect is reading the bounded head sample of unpublished rows.
	StageSelect OutboxStage = "select"
	// StagePublish is the Redis pipeline that carries the batch.
	StagePublish OutboxStage = "publish"
	// StageMarkPublished is the update that retires the published rows.
	StageMarkPublished OutboxStage = "mark_published"
)

// OutboxMetrics publishes the transactional outbox relay's health signals.
//
// The queue depth is a *bounded head sample*, not count(*): a count over an
// outbox with a real backlog is a sequential scan, so the relay would be adding
// database pressure in order to report that it cannot keep up. A sample equal to
// the batch size means at least one full batch is waiting, which is the fact
// worth alarming on.
//
// Every record method is safe on a nil receiver, so a service that exports no
// metrics needs no condition of its own.
type OutboxMetrics struct {
	// Read by the observable callbacks, written by the relay. Atomics because
	// collection runs on the SDK's goroutine, not the relay's.
	sampleRows  atomic.Int64
	oldestAgeMs atomic.Int64
	published   metric.Int64Counter
	failures    metric.Int64Counter
}

// NewOutboxMetrics builds the relay's instruments on meter. A nil meter yields a
// set that records nothing.
func NewOutboxMetrics(meter metric.Meter) (*OutboxMetrics, error) {
	if meter == nil {
		return nil, nil
	}
	metrics := &OutboxMetrics{}
	unpublishedSample, err := meter.Int64ObservableGauge(
		"train_ticket.outbox.unpublished.sample",
		metric.WithDescription("Rows visible in the relay's bounded unpublished head sample."),
		metric.WithUnit("{event}"),
	)
	if err != nil {
		return nil, err
	}
	oldestAge, err := meter.Float64ObservableGauge(
		"train_ticket.outbox.oldest_unpublished.age",
		metric.WithDescription("Age of the head-of-line unpublished outbox event."),
		metric.WithUnit("s"),
	)
	if err != nil {
		return nil, err
	}
	metrics.published, err = meter.Int64Counter(
		"train_ticket.outbox.published",
		metric.WithDescription("Outbox events whose bounded Redis publish pipeline completed successfully."),
		metric.WithUnit("{event}"),
	)
	if err != nil {
		return nil, err
	}
	metrics.failures, err = meter.Int64Counter(
		"train_ticket.outbox.failures",
		metric.WithDescription("Outbox relay failures by a bounded processing stage."),
		metric.WithUnit("{failure}"),
	)
	if err != nil {
		return nil, err
	}
	if _, err := meter.RegisterCallback(func(_ context.Context, observer metric.Observer) error {
		observer.ObserveInt64(unpublishedSample, metrics.sampleRows.Load())
		observer.ObserveFloat64(oldestAge, float64(metrics.oldestAgeMs.Load())/1000.0)
		return nil
	}, unpublishedSample, oldestAge); err != nil {
		return nil, err
	}
	return metrics, nil
}

// ObserveUnpublishedSample records the head of the relay's next batch. rows is
// what the bounded query returned, never a count of the table, and oldestAge is
// the age of the oldest row in that sample. A drained outbox records zero of
// each, which is what makes the pair readable as a level rather than as a
// leftover from the busiest poll.
func (metrics *OutboxMetrics) ObserveUnpublishedSample(rows int, oldestAge time.Duration) {
	if metrics == nil {
		return
	}
	if rows < 0 {
		rows = 0
	}
	millis := oldestAge.Milliseconds()
	if millis < 0 {
		millis = 0
	}
	metrics.sampleRows.Store(int64(rows))
	metrics.oldestAgeMs.Store(millis)
}

// RecordPublished counts rows whose bounded Redis pipeline completed
// successfully.
func (metrics *OutboxMetrics) RecordPublished(rows int) {
	if metrics == nil || metrics.published == nil || rows <= 0 {
		return
	}
	metrics.published.Add(context.Background(), int64(rows))
}

// RecordFailure counts one failure by the stage that raised it.
func (metrics *OutboxMetrics) RecordFailure(stage OutboxStage) {
	if metrics == nil || metrics.failures == nil {
		return
	}
	metrics.failures.Add(context.Background(), 1, metric.WithAttributes(attribute.String("stage", string(stage))))
}

// defaultOutboxMetrics is built once, on first use, from the global meter. The
// global provider is a no-op until a process installs an SDK provider, so a
// service that exports no metrics records nothing without a condition of its own.
var defaultOutboxMetrics = sync.OnceValue(func() *OutboxMetrics {
	metrics, err := NewOutboxMetrics(goruntime.MeterFromEnv(""))
	if err != nil {
		// A gauge that cannot be built is not a reason to stop relaying events:
		// publishing is the job, and the metric is a diagnostic.
		log.Printf("WARN go-kit outbox metrics disabled: %T: %v", err, err)
		return nil
	}
	return metrics
})
