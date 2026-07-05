package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	redis "github.com/redis/go-redis/v9"
)

const (
	EnvelopeField       = "envelope"
	StreamPrefix        = "events:"
	DeadLetterSuffix    = ":dlq"
	MaxLen              = 100000
	MaxDeliveryAttempts = int64(5)
)

type RedisConfig struct {
	URL             string
	ReadBlock       time.Duration
	RecoveryEvery   time.Duration
	RecoveryMinIdle time.Duration
}

type RedisEventBus struct {
	client *redis.Client
	cfg    RedisConfig
	wg     sync.WaitGroup
	dedup  *InMemoryDedupStore
	mu     sync.Mutex
	cancel []context.CancelFunc
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
	return &RedisEventBus{client: client, cfg: cfg, dedup: NewInMemoryDedupStore()}
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
func (b *RedisEventBus) consumeLoop(ctx context.Context, streams []string, group, consumer string, handler Handler) {
	ticker := time.NewTicker(b.cfg.RecoveryEvery)
	defer ticker.Stop()
	ids := make([]string, len(streams))
	for i := range ids {
		ids[i] = ">"
	}
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
			continue
		}
		for _, stream := range result {
			for _, msg := range stream.Messages {
				b.processMessage(ctx, stream.Stream, group, msg, handler)
			}
		}
	}
}
func (b *RedisEventBus) recoverPending(ctx context.Context, stream, group, consumer string, handler Handler) {
	start := "0-0"
	for {
		messages, next, err := b.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{Stream: stream, Group: group, Consumer: consumer, MinIdle: b.cfg.RecoveryMinIdle, Start: start, Count: 100}).Result()
		if err != nil || len(messages) == 0 {
			return
		}
		for _, m := range messages {
			b.processMessage(ctx, stream, group, m, handler)
		}
		if next == "0-0" || next == start {
			return
		}
		start = next
	}
}
func (b *RedisEventBus) processMessage(ctx context.Context, stream, group string, message redis.XMessage, handler Handler) {
	raw, ok := message.Values[EnvelopeField].(string)
	if !ok || strings.TrimSpace(raw) == "" {
		b.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		return
	}
	attempts := b.deliveryAttempts(ctx, stream, group, message.ID)
	var envelope EventEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil || envelope.Validate() != nil {
		b.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
		return
	}
	if b.dedup != nil && b.dedup.Seen(envelope.EventID) {
		_ = b.client.XAck(ctx, stream, group, message.ID).Err()
		return
	}
	if err := handler(ctx, envelope); err != nil {
		if IsFatalHandlerError(err) || attempts >= MaxDeliveryAttempts {
			b.moveToDLQAndAck(ctx, stream, group, message.ID, raw)
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
func (b *RedisEventBus) moveToDLQAndAck(ctx context.Context, stream, group, id, raw string) {
	_ = b.client.XAdd(ctx, &redis.XAddArgs{Stream: stream + DeadLetterSuffix, MaxLen: MaxLen, Approx: true, Values: map[string]any{EnvelopeField: raw}}).Err()
	_ = b.client.XAck(ctx, stream, group, id).Err()
}
