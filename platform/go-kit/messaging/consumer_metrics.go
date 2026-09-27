package messaging

import (
	"context"
	"log"
	"sync"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// ConsumerOutcome is the disposition of one delivered event.
//
// The vocabulary is closed on purpose. Stream names, consumer names, event types
// and business identifiers are useful in traces and logs, but as metric
// attributes they would create a series per integration or entity. These four
// outcomes are enough to separate healthy processing, deduplication, retry
// pressure and poison-message handling without that cardinality.
type ConsumerOutcome string

const (
	// OutcomeSuccess is a delivery whose handler completed and was acknowledged.
	OutcomeSuccess ConsumerOutcome = "success"
	// OutcomeDuplicate is a delivery this consumer group had already processed.
	OutcomeDuplicate ConsumerOutcome = "duplicate"
	// OutcomeRetry is a delivery left pending for Redis to redeliver.
	OutcomeRetry ConsumerOutcome = "retry"
	// OutcomeDeadLetter is a delivery written to the dead-letter stream.
	OutcomeDeadLetter ConsumerOutcome = "dead_letter"
)

// ConsumerFailure is the bounded classification of why a delivery did not
// succeed, and equally the reason a dead letter was written.
//
// The values are the Java platform kit's contract; the Go subscriber reaches
// them through its own branches. See RedisEventBus.processMessage for which
// branch records which value.
type ConsumerFailure string

const (
	// FailureHandlerException is a handler that returned an error the subscriber
	// cannot classify: the Go counterpart of Java's handler exception.
	FailureHandlerException ConsumerFailure = "handler_exception"
	// FailureTransientResult is a handler that declared its error retryable.
	FailureTransientResult ConsumerFailure = "transient_result"
	// FailureFatalResult is a handler that declared its error unrecoverable.
	FailureFatalResult ConsumerFailure = "fatal_result"
	// FailureMissingEnvelope is an entry with no envelope field at all.
	FailureMissingEnvelope ConsumerFailure = "missing_envelope"
	// FailureInvalidEnvelope is an envelope that does not parse or does not validate.
	FailureInvalidEnvelope ConsumerFailure = "invalid_envelope"
	// FailureRedeliveryExhausted is an unclassified failure retired by the
	// redelivery budget rather than by anything the handler said.
	FailureRedeliveryExhausted ConsumerFailure = "redelivery_exhausted"
	// FailureDLQWrite is a dead-letter write that did not land, so the original
	// message is still pending and a later delivery retries the hand-off.
	FailureDLQWrite ConsumerFailure = "dlq_write"
)

// EventConsumerMetrics publishes the process-level transport counters for one
// subscriber. A count and a current population answer different questions: this
// counter says messages were dead-lettered over time, while
// train_ticket.event.dlq.entries is what is sitting in the stream now.
//
// Every record method is safe on a nil receiver and on a set built from a no-op
// meter, so a service that exports no metrics needs no condition of its own.
type EventConsumerMetrics struct {
	messages    metric.Int64Counter
	failures    metric.Int64Counter
	deadLetters metric.Int64Counter
}

// NewEventConsumerMetrics builds the three counters on meter. A nil meter, a
// meter that cannot build an instrument, and the no-op global meter a process
// without an SDK provider has all yield a set that records nothing.
func NewEventConsumerMetrics(meter metric.Meter) (*EventConsumerMetrics, error) {
	if meter == nil {
		return nil, nil
	}
	messages, err := meter.Int64Counter(
		"train_ticket.event.consumer.messages",
		metric.WithDescription("Event deliveries by their processing disposition."),
		metric.WithUnit("{message}"),
	)
	if err != nil {
		return nil, err
	}
	failures, err := meter.Int64Counter(
		"train_ticket.event.consumer.failures",
		metric.WithDescription("Event-consumer failures by a bounded failure classification."),
		metric.WithUnit("{failure}"),
	)
	if err != nil {
		return nil, err
	}
	deadLetters, err := meter.Int64Counter(
		"train_ticket.event.consumer.dead_letters",
		metric.WithDescription("Messages successfully handed to a dead-letter stream."),
		metric.WithUnit("{message}"),
	)
	if err != nil {
		return nil, err
	}
	return &EventConsumerMetrics{messages: messages, failures: failures, deadLetters: deadLetters}, nil
}

// RecordOutcome counts one delivery by what happened to it.
func (metrics *EventConsumerMetrics) RecordOutcome(outcome ConsumerOutcome) {
	if metrics == nil || metrics.messages == nil {
		return
	}
	metrics.messages.Add(context.Background(), 1, metric.WithAttributes(attribute.String("outcome", string(outcome))))
}

// RecordFailure counts one failure by its bounded classification. A retry below
// the give-up threshold is recorded here as well as in RecordOutcome: the
// outcome is the disposition of the delivery, the failure is why it happened.
func (metrics *EventConsumerMetrics) RecordFailure(failure ConsumerFailure) {
	if metrics == nil || metrics.failures == nil {
		return
	}
	metrics.failures.Add(context.Background(), 1, metric.WithAttributes(attribute.String("failure.type", string(failure))))
}

// RecordDeadLetter counts one message successfully handed to the dead-letter
// stream. It is recorded only once the write has landed, so the counter and the
// stream population cannot disagree about what was moved.
func (metrics *EventConsumerMetrics) RecordDeadLetter(reason ConsumerFailure) {
	if metrics == nil || metrics.deadLetters == nil {
		return
	}
	metrics.deadLetters.Add(context.Background(), 1, metric.WithAttributes(attribute.String("reason", string(reason))))
}

// defaultEventConsumerMetrics is built once, on first use.
//
// The meter comes from the global provider, which MeterFromEnv documents as a
// no-op until a process installs an SDK provider. A service that exports no
// metrics therefore records nothing without a condition of its own, and a
// service that does has its instruments created after the provider is installed.
var defaultEventConsumerMetrics = sync.OnceValue(func() *EventConsumerMetrics {
	metrics, err := NewEventConsumerMetrics(goruntime.MeterFromEnv(""))
	if err != nil {
		// A counter that cannot be built is not a reason to stop delivering
		// events: the transport is the job, and the metric is a diagnostic.
		log.Printf("WARN go-kit event consumer metrics disabled: %T: %v", err, err)
		return nil
	}
	return metrics
})
