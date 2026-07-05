package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

const (
	streamPrefix = "events:"
	maxLen       = 100000
)

type RedisPublisher struct {
	client redis.Cmdable
}

func NewRedisPublisher(client redis.Cmdable) *RedisPublisher {
	return &RedisPublisher{client: client}
}

func (p *RedisPublisher) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	data, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	stream := streamPrefix + strings.TrimSpace(envelope.Producer)
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(25*(1<<attempt)) * time.Millisecond):
			}
		}
		_, err = p.client.XAdd(ctx, &redis.XAddArgs{Stream: stream, MaxLen: maxLen, Approx: true, Values: map[string]interface{}{"envelope": string(data)}}).Result()
		if err == nil {
			return nil
		}
		lastErr = err
	}
	return fmt.Errorf("publish failed: %w", lastErr)
}
