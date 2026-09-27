package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	miniredis "github.com/alicebob/miniredis/v2"
	redis "github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel/attribute"
	metricsdk "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

// The contract's names, units and descriptions are the Java platform kit's,
// word for word. They are repeated here rather than referenced so a rename in
// either kit fails this test instead of silently forking the series.
const (
	messagesInstrument    = "train_ticket.event.consumer.messages"
	failuresInstrument    = "train_ticket.event.consumer.failures"
	deadLettersInstrument = "train_ticket.event.consumer.dead_letters"
)

func TestEventConsumerMetricsPublishContractNamesAndAttributes(t *testing.T) {
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })

	metrics, err := NewEventConsumerMetrics(provider.Meter("go-kit-test"))
	if err != nil {
		t.Fatalf("build consumer metrics: %v", err)
	}
	metrics.RecordOutcome(OutcomeSuccess)
	metrics.RecordOutcome(OutcomeDuplicate)
	metrics.RecordOutcome(OutcomeRetry)
	metrics.RecordOutcome(OutcomeDeadLetter)
	metrics.RecordFailure(FailureHandlerException)
	metrics.RecordFailure(FailureTransientResult)
	metrics.RecordFailure(FailureFatalResult)
	metrics.RecordFailure(FailureMissingEnvelope)
	metrics.RecordFailure(FailureInvalidEnvelope)
	metrics.RecordFailure(FailureRedeliveryExhausted)
	metrics.RecordFailure(FailureDLQWrite)
	metrics.RecordDeadLetter(FailureFatalResult)
	metrics.RecordDeadLetter(FailureRedeliveryExhausted)

	collected := collectTransportMetrics(t, reader)

	messagesMetric := metricFor(t, collected, messagesInstrument)
	if messagesMetric.Unit != "{message}" || messagesMetric.Description != "Event deliveries by their processing disposition." {
		t.Fatalf("messages instrument metadata drifted: unit=%q description=%q", messagesMetric.Unit, messagesMetric.Description)
	}
	messages := sumOf(t, messagesMetric)
	for _, outcome := range []string{"success", "duplicate", "retry", "dead_letter"} {
		if got := sumBy(t, messages, map[string]string{"outcome": outcome}); got != 1 {
			t.Errorf("messages{outcome=%s} = %d, want 1", outcome, got)
		}
	}
	if len(messages.DataPoints) != 4 {
		t.Errorf("messages must carry exactly the four closed outcomes, got %d series", len(messages.DataPoints))
	}

	failuresMetric := metricFor(t, collected, failuresInstrument)
	if failuresMetric.Unit != "{failure}" || failuresMetric.Description != "Event-consumer failures by a bounded failure classification." {
		t.Fatalf("failures instrument metadata drifted: unit=%q description=%q", failuresMetric.Unit, failuresMetric.Description)
	}
	failures := sumOf(t, failuresMetric)
	for _, failure := range []string{
		"handler_exception", "transient_result", "fatal_result", "missing_envelope",
		"invalid_envelope", "redelivery_exhausted", "dlq_write",
	} {
		if got := sumBy(t, failures, map[string]string{"failure.type": failure}); got != 1 {
			t.Errorf("failures{failure.type=%s} = %d, want 1", failure, got)
		}
	}
	if len(failures.DataPoints) != 7 {
		t.Errorf("failures must carry exactly the seven closed classifications, got %d series", len(failures.DataPoints))
	}

	deadLettersMetric := metricFor(t, collected, deadLettersInstrument)
	if deadLettersMetric.Unit != "{message}" || deadLettersMetric.Description != "Messages successfully handed to a dead-letter stream." {
		t.Fatalf("dead-letter instrument metadata drifted: unit=%q description=%q", deadLettersMetric.Unit, deadLettersMetric.Description)
	}
	deadLetters := sumOf(t, deadLettersMetric)
	if got := sumBy(t, deadLetters, map[string]string{"reason": "fatal_result"}); got != 1 {
		t.Errorf("dead_letters{reason=fatal_result} = %d, want 1", got)
	}
	if got := sumBy(t, deadLetters, map[string]string{"reason": "redelivery_exhausted"}); got != 1 {
		t.Errorf("dead_letters{reason=redelivery_exhausted} = %d, want 1", got)
	}
}

// A counter built without a meter must record nothing rather than panic: a
// service that exports no metrics calls the same code path.
func TestEventConsumerMetricsWithoutMeterIsInert(t *testing.T) {
	metrics, err := NewEventConsumerMetrics(nil)
	if err != nil {
		t.Fatalf("a nil meter is a normal configuration: %v", err)
	}
	if metrics != nil {
		t.Fatalf("a nil meter must not produce an instrument set, got %#v", metrics)
	}
	var nilMetrics *EventConsumerMetrics
	nilMetrics.RecordOutcome(OutcomeSuccess)
	nilMetrics.RecordFailure(FailureDLQWrite)
	nilMetrics.RecordDeadLetter(FailureFatalResult)
}

func TestRedisSubscriberRecordsSuccessAndDuplicate(t *testing.T) {
	harness := newMetricsHarness(t)
	ctx := context.Background()
	stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"
	envelope := harness.envelope(t, "JourneyOrderConfirmed")
	body := harness.body(t, envelope)

	message := harness.claim(t, ctx, stream, group, consumer, body)
	if ack := harness.bus.processMessage(ctx, stream, group, consumer, message, func(context.Context, EventEnvelope) error {
		return nil
	}); !ack {
		t.Fatal("a successful handler must acknowledge the entry")
	}

	// Same event id, redelivered: dedup swallows it without running the handler.
	duplicate := harness.claim(t, ctx, stream, group, consumer, body)
	if ack := harness.bus.processMessage(ctx, stream, group, consumer, duplicate, func(context.Context, EventEnvelope) error {
		t.Fatal("a duplicate must not reach the handler")
		return nil
	}); !ack {
		t.Fatal("a duplicate must be acknowledged")
	}

	collected := collectTransportMetrics(t, harness.reader)
	messages := sumOf(t, metricFor(t, collected, messagesInstrument))
	if got := sumBy(t, messages, map[string]string{"outcome": "success"}); got != 1 {
		t.Errorf("messages{outcome=success} = %d, want 1", got)
	}
	if got := sumBy(t, messages, map[string]string{"outcome": "duplicate"}); got != 1 {
		t.Errorf("messages{outcome=duplicate} = %d, want 1", got)
	}
	if _, present := collected[failuresInstrument]; present {
		t.Errorf("a success and a duplicate are not failures, got %v", keysOf(collected))
	}
}

func TestRedisSubscriberRecordsRetryBelowTheGiveUpLimit(t *testing.T) {
	harness := newMetricsHarness(t)
	ctx := context.Background()
	stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"
	body := harness.body(t, harness.envelope(t, "RetryEvent"))

	message := harness.claim(t, ctx, stream, group, consumer, body)
	if ack := harness.bus.processMessage(ctx, stream, group, consumer, message, func(context.Context, EventEnvelope) error {
		return TransientHandlerError(errors.New("redis temporarily unavailable"))
	}); ack {
		t.Fatal("a retryable failure must stay pending, not be acknowledged")
	}

	plain := harness.body(t, harness.envelope(t, "BareFailureEvent"))
	plainMessage := harness.claim(t, ctx, stream, group, consumer, plain)
	if ack := harness.bus.processMessage(ctx, stream, group, consumer, plainMessage, func(context.Context, EventEnvelope) error {
		return errors.New("unexpected wiring bug")
	}); ack {
		t.Fatal("an unclassified failure must stay pending, not be acknowledged")
	}

	collected := collectTransportMetrics(t, harness.reader)
	messages := sumOf(t, metricFor(t, collected, messagesInstrument))
	if got := sumBy(t, messages, map[string]string{"outcome": "retry"}); got != 2 {
		t.Errorf("messages{outcome=retry} = %d, want 2", got)
	}
	failures := sumOf(t, metricFor(t, collected, failuresInstrument))
	if got := sumBy(t, failures, map[string]string{"failure.type": "transient_result"}); got != 1 {
		t.Errorf("failures{failure.type=transient_result} = %d, want 1", got)
	}
	if got := sumBy(t, failures, map[string]string{"failure.type": "handler_exception"}); got != 1 {
		t.Errorf("failures{failure.type=handler_exception} = %d, want 1", got)
	}
	if dead, err := harness.client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result(); err != nil || len(dead) != 0 {
		t.Fatalf("a retry below the limit must not dead-letter: %#v err=%v", dead, err)
	}
	if _, present := collected[deadLettersInstrument]; present {
		t.Error("nothing was dead-lettered, so the dead-letter counter must be absent")
	}
}

func TestRedisSubscriberRecordsDeadLetterWhenRedeliveriesAreExhausted(t *testing.T) {
	t.Run("transient result", func(t *testing.T) {
		harness := newMetricsHarness(t)
		ctx := context.Background()
		stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"
		body := harness.body(t, harness.envelope(t, "ExhaustedTransientEvent"))
		message := harness.claim(t, ctx, stream, group, consumer, body)
		harness.exhaust(ctx, stream, group, consumer, message.ID)

		if ack := harness.bus.processMessage(ctx, stream, group, consumer, message, func(context.Context, EventEnvelope) error {
			return TransientHandlerError(errors.New("dependency still unavailable"))
		}); ack {
			t.Fatal("the subscriber acknowledges the entry itself after dead-lettering it")
		}

		collected := collectTransportMetrics(t, harness.reader)
		messages := sumOf(t, metricFor(t, collected, messagesInstrument))
		if got := sumBy(t, messages, map[string]string{"outcome": "dead_letter"}); got != 1 {
			t.Errorf("messages{outcome=dead_letter} = %d, want 1", got)
		}
		if got := sumBy(t, messages, map[string]string{"outcome": "retry"}); got != 0 {
			t.Errorf("an exhausted retry is not a retry, got %d", got)
		}
		failures := sumOf(t, metricFor(t, collected, failuresInstrument))
		if got := sumBy(t, failures, map[string]string{"failure.type": "transient_result"}); got != 1 {
			t.Errorf("failures{failure.type=transient_result} = %d, want 1", got)
		}
		deadLetters := sumOf(t, metricFor(t, collected, deadLettersInstrument))
		if got := sumBy(t, deadLetters, map[string]string{"reason": "transient_result"}); got != 1 {
			t.Errorf("dead_letters{reason=transient_result} = %d, want 1", got)
		}
		harness.assertDeadLettered(t, ctx, stream, 1)
	})

	t.Run("unclassified failure retired by the delivery budget", func(t *testing.T) {
		harness := newMetricsHarness(t)
		ctx := context.Background()
		stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"
		body := harness.body(t, harness.envelope(t, "ExhaustedBareEvent"))
		message := harness.claim(t, ctx, stream, group, consumer, body)
		harness.exhaust(ctx, stream, group, consumer, message.ID)

		harness.bus.processMessage(ctx, stream, group, consumer, message, func(context.Context, EventEnvelope) error {
			return errors.New("still not classified")
		})

		collected := collectTransportMetrics(t, harness.reader)
		failures := sumOf(t, metricFor(t, collected, failuresInstrument))
		if got := sumBy(t, failures, map[string]string{"failure.type": "redelivery_exhausted"}); got != 1 {
			t.Errorf("failures{failure.type=redelivery_exhausted} = %d, want 1", got)
		}
		deadLetters := sumOf(t, metricFor(t, collected, deadLettersInstrument))
		if got := sumBy(t, deadLetters, map[string]string{"reason": "redelivery_exhausted"}); got != 1 {
			t.Errorf("dead_letters{reason=redelivery_exhausted} = %d, want 1", got)
		}
	})
}

func TestRedisSubscriberRecordsFatalAndMalformedEnvelopes(t *testing.T) {
	harness := newMetricsHarness(t)
	ctx := context.Background()
	stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"

	fatal := harness.claim(t, ctx, stream, group, consumer, harness.body(t, harness.envelope(t, "PoisonEvent")))
	harness.bus.processMessage(ctx, stream, group, consumer, fatal, func(context.Context, EventEnvelope) error {
		return FatalHandlerError(errors.New("poison root cause"))
	})

	invalid := harness.claimRaw(t, ctx, stream, group, consumer, map[string]any{EnvelopeField: "not-an-envelope"})
	harness.bus.processMessage(ctx, stream, group, consumer, invalid, func(context.Context, EventEnvelope) error {
		t.Fatal("a malformed envelope must never reach the handler")
		return nil
	})

	missing := harness.claimRaw(t, ctx, stream, group, consumer, map[string]any{"somethingElse": "x"})
	harness.bus.processMessage(ctx, stream, group, consumer, missing, func(context.Context, EventEnvelope) error {
		t.Fatal("an entry without an envelope must never reach the handler")
		return nil
	})

	collected := collectTransportMetrics(t, harness.reader)
	messages := sumOf(t, metricFor(t, collected, messagesInstrument))
	if got := sumBy(t, messages, map[string]string{"outcome": "dead_letter"}); got != 3 {
		t.Errorf("messages{outcome=dead_letter} = %d, want 3", got)
	}
	failures := sumOf(t, metricFor(t, collected, failuresInstrument))
	for failure, want := range map[string]int64{"fatal_result": 1, "invalid_envelope": 1, "missing_envelope": 1} {
		if got := sumBy(t, failures, map[string]string{"failure.type": failure}); got != want {
			t.Errorf("failures{failure.type=%s} = %d, want %d", failure, got, want)
		}
	}
	deadLetters := sumOf(t, metricFor(t, collected, deadLettersInstrument))
	for reason, want := range map[string]int64{"fatal_result": 1, "invalid_envelope": 1, "missing_envelope": 1} {
		if got := sumBy(t, deadLetters, map[string]string{"reason": reason}); got != want {
			t.Errorf("dead_letters{reason=%s} = %d, want %d", reason, got, want)
		}
	}
	harness.assertDeadLettered(t, ctx, stream, 3)
}

// A dead-letter write that did not land is its own failure, and the message has
// to stay pending so a later delivery retries the hand-off.
func TestRedisSubscriberRecordsDeadLetterWriteFailure(t *testing.T) {
	harness := newMetricsHarness(t)
	ctx := context.Background()
	stream, group, consumer := StreamName("payment"), "journey-order", "consumer-1"
	body := harness.body(t, harness.envelope(t, "PoisonEvent"))
	message := harness.claim(t, ctx, stream, group, consumer, body)

	// A group whose DLQ write cannot land: the message is still there, but the
	// push to Redis fails.
	harness.server.Close()
	harness.bus.processMessage(ctx, stream, group, consumer, message, func(context.Context, EventEnvelope) error {
		return FatalHandlerError(errors.New("poison root cause"))
	})

	collected := collectTransportMetrics(t, harness.reader)
	failures := sumOf(t, metricFor(t, collected, failuresInstrument))
	if got := sumBy(t, failures, map[string]string{"failure.type": "fatal_result"}); got != 1 {
		t.Errorf("failures{failure.type=fatal_result} = %d, want 1", got)
	}
	if got := sumBy(t, failures, map[string]string{"failure.type": "dlq_write"}); got != 1 {
		t.Errorf("failures{failure.type=dlq_write} = %d, want 1", got)
	}
	if metric, present := collected[messagesInstrument]; present {
		if got := sumBy(t, sumOf(t, metric), map[string]string{"outcome": "dead_letter"}); got != 0 {
			t.Errorf("a dead letter that never landed must not be counted, got %d", got)
		}
	}
	if _, present := collected[deadLettersInstrument]; present {
		t.Error("a dead letter that never landed must not be counted as written")
	}
}

type metricsHarness struct {
	t       *testing.T
	bus     *RedisEventBus
	client  *redis.Client
	server  *miniredis.Miniredis
	reader  metricsdk.Reader
	metrics *EventConsumerMetrics
}

func newMetricsHarness(t *testing.T) *metricsHarness {
	t.Helper()
	reader := metricsdk.NewManualReader()
	provider := metricsdk.NewMeterProvider(metricsdk.WithReader(reader))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })
	metrics, err := NewEventConsumerMetrics(provider.Meter("go-kit-test"))
	if err != nil {
		t.Fatalf("build consumer metrics: %v", err)
	}
	server := miniredis.RunT(t)
	// No dial retries: one test closes the server to force a DLQ write failure,
	// and the default five attempts would spend seconds reconnecting to a port
	// that is deliberately gone.
	client := redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1, DialTimeout: 250 * time.Millisecond})
	bus := NewRedisEventBusWithClient(client, RedisConfig{ReadBlock: 10 * time.Millisecond, RecoveryEvery: time.Hour}).WithMetrics(metrics)
	t.Cleanup(func() { _ = bus.Close() })
	return &metricsHarness{t: t, bus: bus, client: client, server: server, reader: reader, metrics: metrics}
}

func (h *metricsHarness) envelope(t *testing.T, eventType string) EventEnvelope {
	t.Helper()
	envelope, err := NewEventEnvelope(eventType, "payment", "0194f2e0-7b3e-7610-0284-5c26e8b0c123", map[string]string{"id": "1"}, EnvelopeOptions{Now: time.Date(2026, 7, 5, 10, 30, 0, 0, time.UTC)})
	if err != nil {
		t.Fatalf("build envelope: %v", err)
	}
	return envelope
}

func (h *metricsHarness) body(t *testing.T, envelope EventEnvelope) string {
	t.Helper()
	raw, err := json.Marshal(envelope)
	if err != nil {
		t.Fatalf("marshal envelope: %v", err)
	}
	return string(raw)
}

// claim appends an envelope and moves it into the group's pending list, which is
// what a delivered but unacknowledged entry looks like to Redis.
func (h *metricsHarness) claim(t *testing.T, ctx context.Context, stream, group, consumer, body string) redis.XMessage {
	t.Helper()
	return h.claimRaw(t, ctx, stream, group, consumer, map[string]any{EnvelopeField: body})
}

func (h *metricsHarness) claimRaw(t *testing.T, ctx context.Context, stream, group, consumer string, values map[string]any) redis.XMessage {
	t.Helper()
	if err := h.bus.ensureGroup(ctx, stream, group); err != nil {
		t.Fatalf("ensure group: %v", err)
	}
	if err := h.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, Values: values}).Err(); err != nil {
		t.Fatalf("append entry: %v", err)
	}
	result, err := h.client.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group: group, Consumer: consumer, Streams: []string{stream, ">"}, Count: 10,
	}).Result()
	if err != nil {
		t.Fatalf("read entry: %v", err)
	}
	if len(result) != 1 || len(result[0].Messages) != 1 {
		t.Fatalf("expected exactly one delivered entry, got %#v", result)
	}
	return result[0].Messages[0]
}

// exhaust advances the Redis redelivery counter to the subscriber's give-up
// threshold without invoking the handler, which is what repeated reclaims of an
// unacknowledged entry do in a running consumer.
func (h *metricsHarness) exhaust(ctx context.Context, stream, group, consumer, id string) {
	h.t.Helper()
	for h.bus.deliveryAttempts(ctx, stream, group, id) < MaxDeliveryAttempts {
		if _, err := h.client.XClaim(ctx, &redis.XClaimArgs{
			Stream: stream, Group: group, Consumer: consumer, MinIdle: 0, Messages: []string{id},
		}).Result(); err != nil {
			h.t.Fatalf("advance the redelivery counter: %v", err)
		}
	}
}

func (h *metricsHarness) assertDeadLettered(t *testing.T, ctx context.Context, stream string, want int) {
	t.Helper()
	dead, err := h.client.XRange(ctx, stream+DeadLetterSuffix, "-", "+").Result()
	if err != nil {
		t.Fatalf("read dead-letter stream: %v", err)
	}
	if len(dead) != want {
		t.Fatalf("expected %d dead letters, got %d", want, len(dead))
	}
}

func collectTransportMetrics(t *testing.T, reader metricsdk.Reader) map[string]metricdata.Metrics {
	t.Helper()
	var collected metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &collected); err != nil {
		t.Fatalf("collect: %v", err)
	}
	byName := map[string]metricdata.Metrics{}
	for _, scope := range collected.ScopeMetrics {
		for _, metric := range scope.Metrics {
			byName[metric.Name] = metric
		}
	}
	return byName
}

// metricFor returns the instrument named name, or fails the test when it was
// never created — an unregistered instrument answers nothing at all.
func metricFor(t *testing.T, collected map[string]metricdata.Metrics, name string) metricdata.Metrics {
	t.Helper()
	metric, present := collected[name]
	if !present {
		t.Fatalf("missing instrument %s; collected %v", name, keysOf(collected))
	}
	return metric
}

func sumOf(t *testing.T, metric metricdata.Metrics) metricdata.Sum[int64] {
	t.Helper()
	sum, ok := metric.Data.(metricdata.Sum[int64])
	if !ok {
		t.Fatalf("%s is %T, want an int64 sum", metric.Name, metric.Data)
	}
	return sum
}

// sumBy returns the value of the one series carrying exactly the given
// attributes. An absent series reads as zero, which is what a reader would see.
func sumBy(t *testing.T, sum metricdata.Sum[int64], attributes map[string]string) int64 {
	t.Helper()
	for _, point := range sum.DataPoints {
		if attributesMatch(point.Attributes, attributes) {
			return point.Value
		}
	}
	return 0
}

func attributesMatch(set attribute.Set, want map[string]string) bool {
	if set.Len() != len(want) {
		return false
	}
	for key, value := range want {
		got, present := set.Value(attribute.Key(key))
		if !present || got.AsString() != value {
			return false
		}
	}
	return true
}

func keysOf(byName map[string]metricdata.Metrics) []string {
	names := make([]string, 0, len(byName))
	for name := range byName {
		names = append(names, name)
	}
	return names
}
