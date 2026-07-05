package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

const (
	envelopeField = "envelope"
	maxLen        = 100000
)

type RedisPublisher struct {
	client *redis.Client
}

func NewRedisPublisher(redisURL string) (*RedisPublisher, error) {
	options, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	return &RedisPublisher{client: redis.NewClient(options)}, nil
}

func (p *RedisPublisher) Close() error {
	if p == nil || p.client == nil {
		return nil
	}
	return p.client.Close()
}

func (p *RedisPublisher) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	if p == nil || p.client == nil {
		return fmt.Errorf("%w: redis client is not configured", application.ErrPublishFailed)
	}
	payload, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("%w: marshal envelope: %v", application.ErrPublishFailed, err)
	}
	stream := streamForProducer(envelope.Producer)
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(attempt*50) * time.Millisecond):
			}
		}
		_, err = p.client.XAdd(ctx, &redis.XAddArgs{
			Stream: stream,
			MaxLen: maxLen, Approx: true,
			Values: map[string]any{envelopeField: string(payload)},
		}).Result()
		if err == nil {
			return nil
		}
		lastErr = err
	}
	return fmt.Errorf("%w: %v", application.ErrPublishFailed, lastErr)
}

func streamForProducer(producer string) string {
	return "events:" + strings.TrimSpace(producer)
}
