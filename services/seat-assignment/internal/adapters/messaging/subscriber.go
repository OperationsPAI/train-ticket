package messaging

import (
	"context"
	"strings"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

const ConsumerGroup = "seat-assignment"

type RedisSubscriber struct{ bus *kitmsg.RedisEventBus }

func NewRedisSubscriber(redisURL string) (*RedisSubscriber, error) {
	bus, err := kitmsg.NewRedisEventBus(redisURL)
	if err != nil {
		return nil, err
	}
	return &RedisSubscriber{bus: bus}, nil
}
func (s *RedisSubscriber) Close() error { return s.bus.Close() }
func (s *RedisSubscriber) Subscribe(ctx context.Context, consumerName string, handler kitmsg.Handler) error {
	if strings.TrimSpace(consumerName) == "" {
		consumerName = "seat-assignment-local"
	}
	return s.bus.Subscribe(ctx, kitmsg.Subscription{Streams: SubscribedStreams(), Group: ConsumerGroup, ConsumerName: consumerName}, handler)
}
func SubscribedStreams() []string {
	return []string{"events:booking-orchestration", "events:entitlement-ticketing", "events:post-sales"}
}
