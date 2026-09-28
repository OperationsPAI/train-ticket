package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	redis "github.com/redis/go-redis/v9"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"go.opentelemetry.io/otel/trace"
)

const (
	EnvelopeField       = "envelope"
	StreamPrefix        = "events:"
	DeadLetterSuffix    = ":dlq"
	MaxDeliveryAttempts = int64(5)
	DeadConsumerMaxIdle = 5 * time.Minute

	// StreamMaxLenEnv overrides the per-stream entry cap.
	StreamMaxLenEnv = "EVENT_STREAM_MAXLEN"
	// DefaultStreamMaxLen caps entries kept per stream. Redis streams are never read
	// destructively, so without a cap every published event stays resident forever and
	// eventually exhausts the Redis memory limit.
	DefaultStreamMaxLen = int64(10000)

	// ReadBatchCount is the number of entries an individual read asks for. One read
	// that returns five hundred entries costs one command, and xreadgroup is the
	// largest command class a single-threaded Redis serves.
	ReadBatchCount = int64(500)

	// AccumulateTickEnv overrides the accumulation tick, the wait before the
	// non-blocking top-up read that turns a burst into one batch and one XACK.
	AccumulateTickEnv = "CONSUMER_ACCUMULATE_TICK_MS"
	// DefaultAccumulateTick is the accumulation tick. XREADGROUP ... BLOCK hands back
	// as soon as a single entry is available, so a reader that keeps up gets one to
	// three entries per round and the one XACK that retires a round is one XACK per
	// entry -- xack was the largest command class Redis served. Waiting a tick first
	// lets a burst accumulate into one round, at the cost of up to a tick of delivery
	// latency, well inside the ten second poll. Zero disables the accumulation.
	DefaultAccumulateTick = 50 * time.Millisecond
)

// MaxLen is the per-stream entry cap applied to every XADD, always with Approx (MAXLEN ~)
// so trimming stays O(1) instead of walking the stream to an exact length.
var MaxLen = StreamMaxLen(os.Getenv(StreamMaxLenEnv))

// AccumulateTick is the resolved accumulation tick. Zero disables the accumulation.
var AccumulateTick = accumulateTick(os.Getenv(AccumulateTickEnv))

// accumulateTick resolves the accumulation tick, logging and falling back to the
// default when the configured value is not a non-negative integer of milliseconds.
func accumulateTick(configured string) time.Duration {
	trimmed := strings.TrimSpace(configured)
	if trimmed == "" {
		return DefaultAccumulateTick
	}
	if millis, err := strconv.ParseInt(trimmed, 10, 64); err == nil && millis >= 0 {
		return time.Duration(millis) * time.Millisecond
	}
	log.Printf("WARN %s=%s is not a non-negative integer; using default %v", AccumulateTickEnv, configured, DefaultAccumulateTick)
	return DefaultAccumulateTick
}

// StreamMaxLen resolves the configured cap, falling back to the default when the value is
// blank, non-numeric or non-positive. Falling back beats trimming to zero: a misconfigured
// cap that silently discarded every event would be the worst outcome.
func StreamMaxLen(configured string) int64 {
	trimmed := strings.TrimSpace(configured)
	if trimmed == "" {
		return DefaultStreamMaxLen
	}
	if maxLen, err := strconv.ParseInt(trimmed, 10, 64); err == nil && maxLen > 0 {
		return maxLen
	}
	log.Printf("WARN %s=%s is not a positive integer; using default %d", StreamMaxLenEnv, configured, DefaultStreamMaxLen)
	return DefaultStreamMaxLen
}

type RedisConfig struct {
	URL             string
	ReadBlock       time.Duration
	RecoveryEvery   time.Duration
	RecoveryMinIdle time.Duration
	// Consumers is the number of reader loops per stream. One loop processes a
	// read batch on its own goroutine, so a stream whose events each need a
	// database write is limited by one handler's latency; several loops share
	// the same group and each registers its own consumer name.
	Consumers int
}

type RedisEventBus struct {
	client   *redis.Client
	cfg      RedisConfig
	wg       sync.WaitGroup
	dedup    *InMemoryDedupStore
	observer Observer
	// metrics overrides the process-wide transport counters. Nil means the
	// default set, which is inert until a process installs an SDK meter
	// provider.
	metrics *EventConsumerMetrics
	mu      sync.Mutex
	cancel  []context.CancelFunc
}

func NewRedisClient(redisURL string) (*redis.Client, error) {
	redisURL = strings.TrimSpace(redisURL)
	if redisURL == "" {
		redisURL = "redis://localhost:6379"
	}
	options, err := redis.ParseURL(redisURL)
	if err == nil {
		return redis.NewClient(options), nil
	}
	parsed, parseErr := url.Parse(redisURL)
	if parseErr != nil || parsed.Scheme == "" {
		return redis.NewClient(&redis.Options{Addr: redisURL}), nil
	}
	return nil, err
}
func NewRedisEventBus(redisURL string) (*RedisEventBus, error) {
	c, err := NewRedisClient(redisURL)
	if err != nil {
		return nil, err
	}
	return NewRedisEventBusWithClient(c, RedisConfig{URL: redisURL}), nil
}
func NewRedisEventBusWithClient(client *redis.Client, cfg RedisConfig) *RedisEventBus {
	if cfg.ReadBlock == 0 {
		cfg.ReadBlock = 2 * time.Second
	}
	if cfg.RecoveryEvery == 0 {
		cfg.RecoveryEvery = time.Second
	}
	if cfg.RecoveryMinIdle == 0 {
		cfg.RecoveryMinIdle = 60 * time.Second
	}
	if cfg.Consumers == 0 {
		cfg.Consumers = consumersFromEnv()
	}
	if cfg.Consumers < 1 {
		cfg.Consumers = 1
	}
	return &RedisEventBus{client: client, cfg: cfg, dedup: NewInMemoryDedupStore(), observer: ObserverFromEnv("")}
}

// consumersFromEnv reads CONSUMER_WORKERS, the number of reader loops per
// stream. Unset or invalid keeps the single loop this service has always run.
func consumersFromEnv() int {
	raw := strings.TrimSpace(os.Getenv("CONSUMER_WORKERS"))
	if raw == "" {
		return 1
	}
	workers, err := strconv.Atoi(raw)
	if err != nil || workers < 1 {
		log.Printf("WARN CONSUMER_WORKERS=%q is not a positive integer; using 1", raw)
		return 1
	}
	if workers > 32 {
		return 32
	}
	return workers
}

// WithObserver overrides the event consumer observer. Nil resets to no-op.
func (b *RedisEventBus) WithObserver(observer Observer) *RedisEventBus {
	if observer == nil {
		observer = NoopObserver()
	}
	b.observer = observer
	return b
}

// WithMetrics overrides the process-level transport counters. Nil restores the
// default set, so a test can inject a set bound to its own meter and a service
// needs no wiring at all.
func (b *RedisEventBus) WithMetrics(metrics *EventConsumerMetrics) *RedisEventBus {
	b.metrics = metrics
	return b
}

// consumerMetrics resolves the counters this bus records through. The returned
// value may be nil, which every record method treats as a no-op.
func (b *RedisEventBus) consumerMetrics() *EventConsumerMetrics {
	if b.metrics != nil {
		return b.metrics
	}
	return defaultEventConsumerMetrics()
}

func (b *RedisEventBus) Ping(ctx context.Context) error {
	if b == nil || b.client == nil {
		return fmt.Errorf("redis client is required")
	}
	return b.client.Ping(ctx).Err()
}

func (b *RedisEventBus) Close() error {
	b.mu.Lock()
	cancels := append([]context.CancelFunc(nil), b.cancel...)
	b.cancel = nil
	b.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
	b.wg.Wait()
	if b.client != nil {
		return b.client.Close()
	}
	return nil
}

func (b *RedisEventBus) Publish(ctx context.Context, envelope EventEnvelope) error {
	envelope.injectTraceContext(ctx)
	if err := envelope.Validate(); err != nil {
		return fmt.Errorf("invalid envelope: %w", err)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	var last error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(attempt*attempt) * 50 * time.Millisecond):
			}
		}
		last = b.client.XAdd(ctx, &redis.XAddArgs{Stream: StreamName(envelope.Producer), MaxLen: MaxLen, Approx: true, Values: map[string]any{EnvelopeField: string(body)}}).Err()
		if last == nil {
			return nil
		}
	}
	return fmt.Errorf("publish failed: %w", last)
}

type Subscription struct {
	Streams      []string
	Group        string
	ConsumerName string
	EventFilter  func(EventEnvelope) bool
}

func StreamName(producer string) string {
	p := strings.TrimSpace(producer)
	if strings.HasPrefix(p, StreamPrefix) {
		return p
	}
	return StreamPrefix + p
}

func (b *RedisEventBus) Subscribe(ctx context.Context, sub Subscription, handler Handler) error {
	if len(sub.Streams) == 0 || strings.TrimSpace(sub.Group) == "" || strings.TrimSpace(sub.ConsumerName) == "" || handler == nil {
		return fmt.Errorf("subscription requires streams, group, consumer name, and handler")
	}
	streams := make([]string, 0, len(sub.Streams))
	for _, s := range sub.Streams {
		stream := StreamName(s)
		streams = append(streams, stream)
		if err := b.ensureGroup(ctx, stream, sub.Group); err != nil {
			return err
		}
	}
	runCtx, cancel := context.WithCancel(ctx)
	b.mu.Lock()
	b.cancel = append(b.cancel, cancel)
	b.mu.Unlock()
	for _, stream := range streams {
		for worker := 0; worker < b.cfg.Consumers; worker++ {
			consumer := sub.ConsumerName
			if b.cfg.Consumers > 1 {
				consumer = fmt.Sprintf("%s-%d", sub.ConsumerName, worker)
			}
			b.wg.Add(1)
			go func(stream, consumer string) {
				defer b.wg.Done()
				b.consumeLoop(runCtx, []string{stream}, sub.Group, consumer, handler, sub.EventFilter)
			}(stream, consumer)
		}
	}
	return nil
}
func (b *RedisEventBus) ensureGroup(ctx context.Context, stream, group string) error {
	err := b.client.XGroupCreateMkStream(ctx, stream, group, "$").Err()
	if err == nil || strings.Contains(strings.ToUpper(err.Error()), "BUSYGROUP") {
		return nil
	}
	return fmt.Errorf("create consumer group %s/%s: %w", stream, group, err)
}
func (b *RedisEventBus) pruneDeadConsumers(ctx context.Context, stream, group, selfName string) {
	consumers, err := b.client.XInfoConsumers(ctx, stream, group).Result()
	if err != nil {
		return // best-effort; stream or group may not exist yet
	}
	for _, c := range consumers {
		if c.Name == selfName {
			continue
		}
		if c.Idle > DeadConsumerMaxIdle && c.Pending == 0 {
			pending := c.Pending
			_ = b.client.XGroupDelConsumer(ctx, stream, group, c.Name).Err()
			log.Printf("INFO %spruned dead consumer %s from %s/%s (idle=%v, pending=%d)", goruntime.TraceLogFields(ctx), c.Name, stream, group, c.Idle, pending)
		}
	}
}
func (b *RedisEventBus) consumeLoop(ctx context.Context, streams []string, group, consumer string, handler Handler, filter func(EventEnvelope) bool) {
	for _, s := range streams {
		b.pruneDeadConsumers(ctx, s, group, consumer)
	}
	ticker := time.NewTicker(b.cfg.RecoveryEvery)
	defer ticker.Stop()
	recoveryCursors := make(map[string]string, len(streams))
	for _, stream := range streams {
		recoveryCursors[stream] = "0-0"
	}
	ids := make([]string, len(streams))
	for i := range ids {
		ids[i] = ">"
	}
	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for _, s := range streams {
				recoveryCursors[s] = b.recoverPending(ctx, s, group, consumer, handler, filter, recoveryCursors[s])
			}
		default:
		}
		result, err := b.readStreams(ctx, streams, ids, group, consumer, b.cfg.ReadBlock)
		if err != nil {
			if errors.Is(err, context.Canceled) || ctx.Err() != nil {
				return
			}
			if errors.Is(err, redis.Nil) {
				continue
			}
			log.Printf("WARN %sservice=%s stream=%s read failed; reconnecting in %v: %T: %v", goruntime.TraceLogFields(ctx), group, strings.Join(streams, ","), backoff, err, err)
			if strings.Contains(strings.ToUpper(err.Error()), "NOGROUP") {
				for _, s := range streams {
					_ = b.ensureGroup(ctx, s, group)
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			if backoff < 30*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = time.Second
		result = b.accumulateRead(ctx, streams, ids, group, consumer, result)
		for _, stream := range result {
			b.processBatch(ctx, stream.Stream, group, consumer, stream.Messages, handler, filter)
		}
	}
}

// readStreams issues one XREADGROUP for every stream this group consumes.
//
// A negative block is the non-blocking form: go-redis omits BLOCK rather than sending
// it, which is what the accumulation top-up needs. Passing zero would instead send
// `BLOCK 0`, which Redis reads as "block forever".
func (b *RedisEventBus) readStreams(ctx context.Context, streams, ids []string, group, consumer string, block time.Duration) ([]redis.XStream, error) {
	return b.client.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group:    group,
		Consumer: consumer,
		Streams:  append(append([]string{}, streams...), ids...),
		Count:    ReadBatchCount,
		Block:    block,
	}).Result()
}

// accumulateRead waits one tick and takes one more, non-blocking, read, so a burst
// of entries arrives as one batch and is retired by one XACK.
//
// The idle path is untouched: only a read that already returned entries pays the
// tick, and an empty read loops straight back to the blocking read, so an empty
// stream never spins. A failed or empty top-up is not fatal to the round; the
// entries already in hand are still processed and the next round re-reads.
func (b *RedisEventBus) accumulateRead(ctx context.Context, streams, ids []string, group, consumer string, first []redis.XStream) []redis.XStream {
	if AccumulateTick <= 0 || countMessages(first) == 0 || countMessages(first) >= ReadBatchCount {
		return first
	}
	select {
	case <-ctx.Done():
		return first
	case <-time.After(AccumulateTick):
	}
	more, err := b.readStreams(ctx, streams, ids, group, consumer, -1)
	if err != nil {
		return first
	}
	return mergeStreams(first, more)
}

func countMessages(streams []redis.XStream) int64 {
	var total int64
	for _, stream := range streams {
		total += int64(len(stream.Messages))
	}
	return total
}

// mergeStreams concatenates one read's messages per stream onto another's, keeping
// the stream order of the first read. Redis caps each read at COUNT, so the merged
// batch is the burst the tick caught, not the batch size times two.
func mergeStreams(first, more []redis.XStream) []redis.XStream {
	if len(more) == 0 {
		return first
	}
	merged := make([]redis.XStream, 0, len(first)+len(more))
	index := make(map[string]int, len(first))
	for _, stream := range first {
		index[stream.Stream] = len(merged)
		merged = append(merged, stream)
	}
	for _, stream := range more {
		if at, ok := index[stream.Stream]; ok {
			merged[at].Messages = append(merged[at].Messages, stream.Messages...)
			continue
		}
		index[stream.Stream] = len(merged)
		merged = append(merged, stream)
	}
	return merged
}

func (b *RedisEventBus) recoverPending(ctx context.Context, stream, group, consumer string, handler Handler, filter func(EventEnvelope) bool, start string) string {
	messages, next, err := b.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumer, MinIdle: b.cfg.RecoveryMinIdle, Start: start, Count: 100}).Result()
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d recovery failed: %T: %v", goruntime.TraceLogFields(ctx), group, stream, "unknown", int64(0), err, err)
		}
		return start
	}
	b.processBatch(ctx, stream, group, consumer, messages, handler, filter)
	return next
}
func (b *RedisEventBus) processBatch(ctx context.Context, stream, group, consumer string, messages []redis.XMessage, handler Handler, filter func(EventEnvelope) bool) {
	// One XACK for the whole batch, not one per entry. XACK takes any number of
	// ids for the same stream and group, and it is the largest command class a
	// single-threaded Redis serves -- about 6000 a second on the resident release
	// against ~12700 in total. Handled, filtered and duplicate entries all end
	// here; an entry left pending on a transient failure is simply not added.
	toAcknowledge := make([]string, 0, len(messages))
	for _, message := range messages {
		if filter != nil {
			raw, ok := message.Values[EnvelopeField].(string)
			var envelope EventEnvelope
			if ok && json.Unmarshal([]byte(raw), &envelope) == nil && envelope.Validate() == nil && !filter(envelope) {
				// Not this consumer's event type: acknowledged, and counted as
				// neither an outcome nor a failure, because the group was never
				// meant to process it.
				toAcknowledge = append(toAcknowledge, message.ID)
				continue
			}
		}
		if b.processMessage(ctx, stream, group, consumer, message, handler) {
			toAcknowledge = append(toAcknowledge, message.ID)
		}
	}
	if len(toAcknowledge) > 0 {
		if err := b.client.XAck(ctx, stream, group, toAcknowledge...).Err(); err != nil {
			log.Printf("WARN %sservice=%s stream=%s batch acknowledgement failed; messages remain pending: %v", goruntime.TraceLogFields(ctx), group, stream, err)
		}
	}
}

// processMessage handles one entry and reports whether the batch should
// acknowledge it. False means the entry stays pending: a transient handler
// failure, or a path that acknowledged it itself after moving it to the dead
// letter.
func (b *RedisEventBus) processMessage(ctx context.Context, stream, group, consumer string, message redis.XMessage, handler Handler) bool {
	ctx = MessageContext(ctx, stream, group)
	metrics := b.consumerMetrics()
	raw, ok := message.Values[EnvelopeField].(string)
	if !ok || strings.TrimSpace(raw) == "" {
		// An entry with no envelope field is not a message this subscriber can
		// even describe, so it is dead-lettered on the first delivery rather
		// than retried.
		metrics.RecordFailure(FailureMissingEnvelope)
		b.moveToDLQAndAck(ctx, stream, group, consumer, message.ID, raw, "MissingEnvelope", 1, FailureMissingEnvelope)
		return false
	}
	// Resolved only where it decides something.
	//
	// This used to run for every delivered message, which is an XPENDING round
	// trip per delivery to a single-threaded Redis: measured on the resident
	// release, XPENDING was about 3000 of its ~16000 commands a second and almost
	// every one of them was thrown away, because the success path never reads the
	// count. The failure, duplicate and malformed paths still ask for it.
	attempts := func() int64 { return b.deliveryAttempts(ctx, stream, group, message.ID) }
	var envelope EventEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil || envelope.Validate() != nil {
		metrics.RecordFailure(FailureInvalidEnvelope)
		b.moveToDLQAndAck(ctx, stream, group, consumer, message.ID, raw, "InvalidEnvelope", attempts(), FailureInvalidEnvelope)
		return false
	}
	// The producer's trace context, so the lines below can be joined to the trace
	// that emitted the event even on the paths where no consumer span is ever
	// started (a duplicate is acked without running the handler).
	if parent, ok := remoteSpanContextFromEnvelope(envelope); ok {
		ctx = trace.ContextWithRemoteSpanContext(ctx, parent)
	}
	if b.dedup != nil && b.dedup.Seen(envelope.EventID) {
		log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d duplicate event already processed; acking without handler", goruntime.TraceLogFields(ctx), group, stream, envelope.EventID, attempts())
		metrics.RecordOutcome(OutcomeDuplicate)
		return true
	}
	// ObservedHandler starts the consumer span and puts it on the context it
	// passes down, but does not hand that context back. The failure and DLQ lines
	// below run after the handler returns, so without capturing it they would
	// report the pre-span context and name the producer's span instead of this
	// consumer's -- pointing an operator at the wrong service for exactly the
	// lines that explain why an event died.
	handlerCtx := ctx
	observedHandler := ObservedHandler(b.observer, stream, group, func(spanCtx context.Context, observed EventEnvelope) error {
		handlerCtx = spanCtx
		return handler(spanCtx, observed)
	})
	if err := observedHandler(ctx, envelope); err != nil {
		deliveries := attempts()
		// The failure classification and the retirement decision are separate
		// facts, so the branch below reads the handler's own classification
		// first and only then asks whether retrying is still allowed.
		switch {
		case IsFatalHandlerError(err):
			// The handler said this can never succeed, so no number of
			// redeliveries is worth making.
			metrics.RecordFailure(FailureFatalResult)
			b.moveToDLQAndAck(handlerCtx, stream, group, consumer, message.ID, raw, err, deliveries, FailureFatalResult)
		case IsTransientHandlerError(err) && deliveries < MaxDeliveryAttempts:
			metrics.RecordFailure(FailureTransientResult)
			metrics.RecordOutcome(OutcomeRetry)
			log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d handler transient failure; message stays pending for retry: %T: %v", goruntime.TraceLogFields(handlerCtx), group, stream, envelope.EventID, deliveries, err, err)
		case IsTransientHandlerError(err):
			metrics.RecordFailure(FailureTransientResult)
			b.moveToDLQAndAck(handlerCtx, stream, group, consumer, message.ID, raw, err, deliveries, FailureTransientResult)
		case deliveries >= MaxDeliveryAttempts:
			// A bare error carries no classification, and the delivery budget
			// is what retires it: after this many hand-backs, retrying is the
			// one thing already known not to work.
			metrics.RecordFailure(FailureRedeliveryExhausted)
			b.moveToDLQAndAck(handlerCtx, stream, group, consumer, message.ID, raw, err, deliveries, FailureRedeliveryExhausted)
		default:
			// A bare error is the Go counterpart of an exception out of a Java
			// handler: a real fault the handler did not classify. Retrying is
			// still the right response, because most such faults are
			// environmental and clear on their own.
			metrics.RecordFailure(FailureHandlerException)
			metrics.RecordOutcome(OutcomeRetry)
			log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d handler failure; message stays pending for retry: %T: %v", goruntime.TraceLogFields(handlerCtx), group, stream, envelope.EventID, deliveries, err, err)
		}
		return false
	}
	if b.dedup != nil {
		b.dedup.Record(envelope.EventID)
	}
	metrics.RecordOutcome(OutcomeSuccess)
	return true
}
func (b *RedisEventBus) deliveryAttempts(ctx context.Context, stream, group, id string) int64 {
	entries, err := b.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: id, End: id, Count: 1}).Result()
	if err != nil || len(entries) == 0 {
		return 1
	}
	return entries[0].RetryCount
}
func (b *RedisEventBus) moveToDLQAndAck(ctx context.Context, stream, group, consumer, id, raw string, reason any, attempts int64, failure ConsumerFailure) {
	failureReason := truncateFailureReason(reason)
	safeAttempts := maxInt64(1, attempts)
	deadLetteredAt := time.Now().UTC().Format(time.RFC3339Nano)
	metrics := b.consumerMetrics()
	log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d consumerGroup=%s failureReason=%s attempts=%d deadLetteredAt=%s moving message to DLQ", goruntime.TraceLogFields(ctx), group, stream, eventIDForLog(raw), safeAttempts, group, failureReason, safeAttempts, deadLetteredAt)
	if err := b.client.XAdd(ctx, &redis.XAddArgs{
		Stream: stream + DeadLetterSuffix,
		MaxLen: MaxLen,
		Approx: true,
		Values: dlqFieldsWithTimestamp(raw, group, consumer, failureReason, safeAttempts, deadLetteredAt),
	}).Err(); err != nil {
		log.Printf("WARN %sservice=%s stream=%s eventId=%s DLQ write failed; original message remains pending: %v", goruntime.TraceLogFields(ctx), group, stream, eventIDForLog(raw), err)
		// The message is still pending, so the next delivery tries the hand-off
		// again; this is its own failure and is counted as one.
		metrics.RecordFailure(FailureDLQWrite)
		return
	}
	// Counted only once the entry is really in the dead-letter stream, so the
	// counter and the population cannot disagree about what was moved.
	metrics.RecordDeadLetter(failure)
	metrics.RecordOutcome(OutcomeDeadLetter)
	if err := b.client.XAck(ctx, stream, group, id).Err(); err != nil {
		log.Printf("WARN %sservice=%s stream=%s eventId=%s DLQ acknowledgement failed; original message remains pending: %v", goruntime.TraceLogFields(ctx), group, stream, eventIDForLog(raw), err)
	}
}

func dlqFields(raw, group, consumer, failureReason string, attempts int64) map[string]any {
	return dlqFieldsWithTimestamp(raw, group, consumer, failureReason, attempts, time.Now().UTC().Format(time.RFC3339Nano))
}

func dlqFieldsWithTimestamp(raw, group, consumer, failureReason string, attempts int64, deadLetteredAt string) map[string]any {
	return map[string]any{
		EnvelopeField:    raw,
		"consumerGroup":  group,
		"consumerName":   consumer,
		"failureReason":  failureReason,
		"attempts":       fmt.Sprintf("%d", maxInt64(1, attempts)),
		"deadLetteredAt": deadLetteredAt,
	}
}

func truncateFailureReason(reason any) string {
	text := fmt.Sprint(reason)
	if err, ok := reason.(error); ok {
		text = fmt.Sprintf("%T: %s", err, err.Error())
	}
	if len(text) > 500 {
		return text[:500]
	}
	if strings.TrimSpace(text) == "" {
		return "unknown"
	}
	return text
}

func eventIDForLog(raw string) string {
	var envelope EventEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil || envelope.EventID == "" {
		return "unknown"
	}
	return envelope.EventID
}

func maxInt64(left, right int64) int64 {
	if left > right {
		return left
	}
	return right
}
