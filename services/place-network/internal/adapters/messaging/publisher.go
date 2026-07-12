package messaging

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/trainticket/greenfield/services/place-network/internal/domain"
)

type streamAppender interface {
	XAdd(context.Context, *redis.XAddArgs) *redis.StringCmd
}

type RedisPublisher struct {
	client streamAppender
}

func NewRedisPublisher(client *redis.Client) *RedisPublisher {
	return NewPublisher(client)
}

func NewPublisher(client streamAppender) *RedisPublisher {
	return &RedisPublisher{client: client}
}

func (p *RedisPublisher) Publish(ctx context.Context, envelope domain.EventEnvelope) error {
	data, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("publish failed: %w", err)
	}
	stream := "events:" + envelope.Producer
	args := &redis.XAddArgs{Stream: stream, MaxLen: MaxLen, Approx: true, Values: map[string]any{"envelope": string(data)}}
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(50*(1<<attempt)) * time.Millisecond)
		}
		if _, err := p.client.XAdd(ctx, args).Result(); err == nil {
			return nil
		} else {
			lastErr = err
		}
	}
	return fmt.Errorf("publish failed: %w", lastErr)
}
