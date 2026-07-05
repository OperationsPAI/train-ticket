package messaging

import (
	"context"

	redis "github.com/redis/go-redis/v9"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/service-plan/internal/application"
)

type RedisEventBus struct{ bus *kitmsg.RedisEventBus }

func NewRedisEventBus(redisURL string) (*RedisEventBus, error) {
	b, err := kitmsg.NewRedisEventBus(redisURL)
	if err != nil {
		return nil, err
	}
	return &RedisEventBus{bus: b}, nil
}
func NewRedisClient(redisURL string) (*redis.Client, error) { return kitmsg.NewRedisClient(redisURL) }
func (b *RedisEventBus) Close() error                       { return b.bus.Close() }
func (b *RedisEventBus) Publish(ctx context.Context, envelope application.EventEnvelope) error {
	return b.bus.Publish(ctx, envelope)
}
func (b *RedisEventBus) Subscribe(ctx context.Context, subscription application.Subscription, handler application.EventHandler) error {
	streams := make([]string, 0, len(subscription.Streams))
	for _, s := range subscription.Streams {
		streams = append(streams, s.Producer)
	}
	return b.bus.Subscribe(ctx, kitmsg.Subscription{Streams: streams, Group: subscription.Group, ConsumerName: subscription.ConsumerName}, kitmsg.Handler(handler))
}
func streamName(producer string) string { return kitmsg.StreamName(producer) }
