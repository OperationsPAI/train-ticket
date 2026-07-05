package messaging

import (
	"context"

	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
)

const (
	ProviderIntegrationGroup   = "provider-integration"
	streamBookingOrchestration = "events:booking-orchestration"
	streamSupplierCatalog      = "events:supplier-catalog"
)

var subscribedStreams = []string{streamBookingOrchestration, streamSupplierCatalog}

type RedisSubscriber struct{ bus *kitmsg.RedisEventBus }

func NewRedisSubscriber(redisURL string) (*RedisSubscriber, error) {
	bus, err := kitmsg.NewRedisEventBus(redisURL)
	if err != nil {
		return nil, err
	}
	return &RedisSubscriber{bus: bus}, nil
}

func (s *RedisSubscriber) Close() error {
	if s == nil || s.bus == nil {
		return nil
	}
	return s.bus.Close()
}

func (s *RedisSubscriber) Subscribe(ctx context.Context, streams []string, group string, consumerName string, handler application.EventHandler) error {
	if len(streams) == 0 {
		streams = subscribedStreams
	}
	if group == "" {
		group = ProviderIntegrationGroup
	}
	if consumerName == "" {
		consumerName = ProviderIntegrationGroup + "-instance"
	}
	return s.bus.Subscribe(ctx, kitmsg.Subscription{Streams: streams, Group: group, ConsumerName: consumerName}, kitmsg.Handler(handler))
}
