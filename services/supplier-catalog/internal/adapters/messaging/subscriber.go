package messaging

import (
	"context"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/supplier-catalog/internal/application"
)

type RedisSubscriber struct{ bus *kitmsg.RedisEventBus }

func NewRedisSubscriber(bus *kitmsg.RedisEventBus) *RedisSubscriber {
	return &RedisSubscriber{bus: bus}
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	return s.bus.Subscribe(ctx, kitmsg.Subscription{Streams: streams, Group: group, ConsumerName: consumerName}, kitmsg.Handler(handler))
}
