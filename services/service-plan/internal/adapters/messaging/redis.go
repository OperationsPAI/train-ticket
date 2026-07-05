package messaging

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	redis "github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/service-plan/internal/application"
)

const (
	envelopeField       = "envelope"
	streamPrefix        = "events:"
	deadLetterSuffix    = ":dlq"
	maxLen              = 100000
	readBlock           = 2 * time.Second
	recoveryEvery       = 60 * time.Second
	recoveryMinIdle     = 60 * time.Second
	maxDeliveryAttempts = int64(5)
)

type RedisEventBus struct {
	client *redis.Client
}

func NewRedisEventBus(redisURL string) (*RedisEventBus, error) {
	client, err := NewRedisClient(redisURL)
	if err != nil {
		return nil, err
	}
	return &RedisEventBus{client: client}, nil
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

func (b *RedisEventBus) Close() error {
	return b.client.Close()
}

func (b *RedisEventBus) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	if err := envelope.Validate(); err != nil {
		return fmt.Errorf("invalid envelope: %w", err)
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	stream := streamName(envelope.Producer)
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(attempt*attempt) * 50 * time.Millisecond):
			}
		}
		lastErr = b.client.XAdd(ctx, &redis.XAddArgs{
			Stream: stream,
			MaxLen: maxLen,
			Approx: true,
			Values: map[string]any{envelopeField: string(body)},
		}).Err()
		if lastErr == nil {
			return nil
		}
	}
	return fmt.Errorf("publish failed: %w", lastErr)
}

func (b *RedisEventBus) Subscribe(ctx context.Context, subscription application.Subscription, handler application.EventHandler) error {
	if len(subscription.Streams) == 0 {
		return fmt.Errorf("subscription streams are required")
	}
	if strings.TrimSpace(subscription.Group) == "" || strings.TrimSpace(subscription.ConsumerName) == "" {
		return fmt.Errorf("subscription group and consumerName are required")
	}
	if handler == nil {
		return fmt.Errorf("handler is required")
	}
	streams := make([]string, 0, len(subscription.Streams))
	for _, eventStream := range subscription.Streams {
		stream := streamName(eventStream.Producer)
		streams = append(streams, stream)
		if err := b.ensureGroup(ctx, stream, subscription.Group); err != nil {
			return fmt.Errorf("create consumer group: %w", err)
		}
	}
	go b.consumeLoop(ctx, streams, subscription.Group, subscription.ConsumerName, handler)
	return nil
}

func (b *RedisEventBus) ensureGroup(ctx context.Context, stream, group string) error {
	err := b.client.XGroupCreateMkStream(ctx, stream, group, "$").Err()
	if err == nil || strings.Contains(strings.ToUpper(err.Error()), "BUSYGROUP") {
		return nil
	}
	return err
}

func (b *RedisEventBus) consumeLoop(ctx context.Context, streams []string, group, consumer string, handler application.EventHandler) {
	recoveryTicker := time.NewTicker(recoveryEvery)
	defer recoveryTicker.Stop()
	streamIDs := make([]string, len(streams))
	for i := range streamIDs {
		streamIDs[i] = ">"
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-recoveryTicker.C:
			for _, stream := range streams {
				b.recoverPending(ctx, stream, group, consumer, handler)
			}
		default:
		}
		result, err := b.client.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    group,
			Consumer: consumer,
			Streams:  append(append([]string{}, streams...), streamIDs...),
			Count:    10,
			Block:    readBlock,
		}).Result()
		if err != nil {
			if errors.Is(err, redis.Nil) || errors.Is(err, context.Canceled) {
				continue
			}
			continue
		}
		for _, stream := range result {
			for _, message := range stream.Messages {
				b.processMessage(ctx, stream.Stream, group, message, handler)
			}
		}
	}
}

func (b *RedisEventBus) recoverPending(ctx context.Context, stream, group, consumer string, handler application.EventHandler) {
	start := "0"
	for {
		messages, next, err := b.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{
			Stream:   stream,
			Group:    group,
			Consumer: consumer,
			MinIdle:  recoveryMinIdle,
			Start:    start,
			Count:    100,
		}).Result()
		if err != nil || len(messages) == 0 {
			return
		}
		for _, message := range messages {
			b.processMessage(ctx, stream, group, message, handler)
		}
		if next == "0-0" || next == start {
			return
		}
		start = next
	}
}

func (b *RedisEventBus) processMessage(ctx context.Context, stream, group string, message redis.XMessage, handler application.EventHandler) {
	envelopeJSON, ok := message.Values[envelopeField].(string)
	if !ok {
		b.moveToDLQAndAck(ctx, stream, group, message.ID, "")
		return
	}
	attempts := b.deliveryAttempts(ctx, stream, group, message.ID)
	if attempts >= maxDeliveryAttempts {
		b.moveToDLQAndAck(ctx, stream, group, message.ID, envelopeJSON)
		return
	}
	var envelope application.EventEnvelope
	if err := json.Unmarshal([]byte(envelopeJSON), &envelope); err != nil || envelope.Validate() != nil {
		b.moveToDLQAndAck(ctx, stream, group, message.ID, envelopeJSON)
		return
	}
	if err := handler(ctx, envelope); err != nil {
		if application.IsFatalHandlerError(err) {
			b.moveToDLQAndAck(ctx, stream, group, message.ID, envelopeJSON)
		}
		return
	}
	_ = b.client.XAck(ctx, stream, group, message.ID).Err()
}

func (b *RedisEventBus) deliveryAttempts(ctx context.Context, stream, group, messageID string) int64 {
	pending, err := b.client.XPendingExt(ctx, &redis.XPendingExtArgs{Stream: stream, Group: group, Start: messageID, End: messageID, Count: 1}).Result()
	if err != nil || len(pending) == 0 {
		return 1
	}
	return pending[0].RetryCount
}

func (b *RedisEventBus) moveToDLQAndAck(ctx context.Context, stream, group, messageID, envelopeJSON string) {
	if envelopeJSON != "" {
		_ = b.client.XAdd(ctx, &redis.XAddArgs{
			Stream: stream + deadLetterSuffix,
			MaxLen: maxLen,
			Approx: true,
			Values: map[string]any{envelopeField: envelopeJSON},
		}).Err()
	}
	_ = b.client.XAck(ctx, stream, group, messageID).Err()
}

func streamName(producer string) string {
	return streamPrefix + strings.TrimSpace(producer)
}
