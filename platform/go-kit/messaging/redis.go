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
)

// MaxLen is the per-stream entry cap applied to every XADD, always with Approx (MAXLEN ~)
// so trimming stays O(1) instead of walking the stream to an exact length.
var MaxLen = StreamMaxLen(os.Getenv(StreamMaxLenEnv))

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
}

type RedisEventBus struct {
	client   *redis.Client
	cfg      RedisConfig
	wg       sync.WaitGroup
	dedup    *InMemoryDedupStore
	observer Observer
	mu       sync.Mutex
	cancel   []context.CancelFunc
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
		cfg.RecoveryEvery = 60 * time.Second
	}
	if cfg.RecoveryMinIdle == 0 {
		cfg.RecoveryMinIdle = 60 * time.Second
	}
	return &RedisEventBus{client: client, cfg: cfg, dedup: NewInMemoryDedupStore(), observer: ObserverFromEnv("")}
}

// WithObserver overrides the event consumer observer. Nil resets to no-op.
func (b *RedisEventBus) WithObserver(observer Observer) *RedisEventBus {
	if observer == nil {
		observer = NoopObserver()
	}
	b.observer = observer
	return b
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
	b.wg.Add(1)
	go func() { defer b.wg.Done(); b.consumeLoop(runCtx, streams, sub.Group, sub.ConsumerName, handler) }()
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
		if c.Idle > DeadConsumerMaxIdle {
			pending := c.Pending
			_ = b.client.XGroupDelConsumer(ctx, stream, group, c.Name).Err()
			log.Printf("INFO %spruned dead consumer %s from %s/%s (idle=%v, pending=%d)", goruntime.TraceLogFields(ctx), c.Name, stream, group, c.Idle, pending)
		}
	}
}
func (b *RedisEventBus) consumeLoop(ctx context.Context, streams []string, group, consumer string, handler Handler) {
	for _, s := range streams {
		b.pruneDeadConsumers(ctx, s, group, consumer)
	}
	ticker := time.NewTicker(b.cfg.RecoveryEvery)
	defer ticker.Stop()
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
				b.recoverPending(ctx, s, group, consumer, handler)
			}
		default:
		}
		result, err := b.client.XReadGroup(ctx, &redis.XReadGroupArgs{Group: group, Consumer: consumer, Streams: append(append([]string{}, streams...), ids...), Count: 10, Block: b.cfg.ReadBlock}).Result()
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
		for _, stream := range result {
			for _, msg := range stream.Messages {
				b.processMessage(ctx, stream.Stream, group, consumer, msg, handler)
			}
		}
	}
}
func (b *RedisEventBus) recoverPending(ctx context.Context, stream, group, consumer string, handler Handler) {
	start := "0-0"
	for {
		messages, next, err := b.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumer, MinIdle: b.cfg.RecoveryMinIdle, Start: start, Count: 100}).Result()
		if err != nil {
			if ctx.Err() == nil {
				log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d recovery failed: %T: %v", goruntime.TraceLogFields(ctx), group, stream, "unknown", int64(0), err, err)
			}
			return
		}
		if len(messages) == 0 {
			return
		}
		for _, m := range messages {
			b.processMessage(ctx, stream, group, consumer, m, handler)
		}
		if next == "0-0" || next == start {
			return
		}
		start = next
	}
}
func (b *RedisEventBus) processMessage(ctx context.Context, stream, group, consumer string, message redis.XMessage, handler Handler) {
	ctx = MessageContext(ctx, stream, group)
	raw, ok := message.Values[EnvelopeField].(string)
	if !ok || strings.TrimSpace(raw) == "" {
		b.moveToDLQAndAck(ctx, stream, group, consumer, message.ID, raw, "MissingEnvelope", 1)
		return
	}
	attempts := b.deliveryAttempts(ctx, stream, group, message.ID)
	var envelope EventEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil || envelope.Validate() != nil {
		b.moveToDLQAndAck(ctx, stream, group, consumer, message.ID, raw, "InvalidEnvelope", attempts)
		return
	}
	// The producer's trace context, so the lines below can be joined to the trace
	// that emitted the event even on the paths where no consumer span is ever
	// started (a duplicate is acked without running the handler).
	if parent, ok := remoteSpanContextFromEnvelope(envelope); ok {
		ctx = trace.ContextWithRemoteSpanContext(ctx, parent)
	}
	if b.dedup != nil && b.dedup.Seen(envelope.EventID) {
		log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d duplicate event already processed; acking without handler", goruntime.TraceLogFields(ctx), group, stream, envelope.EventID, attempts)
		_ = b.client.XAck(ctx, stream, group, message.ID).Err()
		return
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
		if IsFatalHandlerError(err) || attempts >= MaxDeliveryAttempts {
			b.moveToDLQAndAck(handlerCtx, stream, group, consumer, message.ID, raw, err, attempts)
		} else {
			log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d handler transient failure; message stays pending for retry: %T: %v", goruntime.TraceLogFields(handlerCtx), group, stream, envelope.EventID, attempts, err, err)
		}
		return
	}
	if b.dedup != nil {
		b.dedup.Record(envelope.EventID)
	}
	_ = b.client.XAck(ctx, stream, group, message.ID).Err()
}
func (b *RedisEventBus) deliveryAttempts(ctx context.Context, stream, group, id string) int64 {
	entries, err := b.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: id, End: id, Count: 1}).Result()
	if err != nil || len(entries) == 0 {
		return 1
	}
	return entries[0].RetryCount
}
func (b *RedisEventBus) moveToDLQAndAck(ctx context.Context, stream, group, consumer, id, raw string, reason any, attempts int64) {
	failureReason := truncateFailureReason(reason)
	safeAttempts := maxInt64(1, attempts)
	deadLetteredAt := time.Now().UTC().Format(time.RFC3339Nano)
	log.Printf("WARN %sservice=%s stream=%s eventId=%s deliveries=%d consumerGroup=%s failureReason=%s attempts=%d deadLetteredAt=%s moving message to DLQ", goruntime.TraceLogFields(ctx), group, stream, eventIDForLog(raw), safeAttempts, group, failureReason, safeAttempts, deadLetteredAt)
	_ = b.client.XAdd(ctx, &redis.XAddArgs{
		Stream: stream + DeadLetterSuffix,
		MaxLen: MaxLen,
		Approx: true,
		Values: dlqFieldsWithTimestamp(raw, group, consumer, failureReason, safeAttempts, deadLetteredAt),
	}).Err()
	_ = b.client.XAck(ctx, stream, group, id).Err()
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
